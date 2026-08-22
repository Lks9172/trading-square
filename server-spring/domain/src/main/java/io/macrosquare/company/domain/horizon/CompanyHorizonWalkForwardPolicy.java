package io.macrosquare.company.domain.horizon;

import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignalPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomPolicy;
import io.macrosquare.company.domain.bottom.ReversalConfirmationEvidence;
import io.macrosquare.company.domain.bottom.ReversalConfirmationPolicy;
import io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy;
import io.macrosquare.company.domain.bottom.PriceStructurePolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Expanding-window, causal validation. Features at index t only see data up to
 * t; t+N prices are read exclusively as outcomes. Fundamentals are excluded to
 * avoid applying today's financial statements to historical dates.
 */
public final class CompanyHorizonWalkForwardPolicy {

    private static final int MINIMUM_HISTORY = 120;
    private static final int FEATURE_LOOKBACK = 380;
    private static final int SIGNAL_THRESHOLD = 70;

    private final BottomPriceContextPolicy contextPolicy;
    private final BottomPriceSignalPolicy priceSignalPolicy;
    private final DeepBottomPolicy deepBottomPolicy;
    private final ReversalConfirmationPolicy reversalPolicy;
    private final VolumePriceConfirmationPolicy technicalPolicy;
    private final PriceStructurePolicy priceStructurePolicy;

    public CompanyHorizonWalkForwardPolicy(
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            VolumePriceConfirmationPolicy technicalPolicy
    ) {
        this.contextPolicy = Objects.requireNonNull(contextPolicy);
        this.priceSignalPolicy = Objects.requireNonNull(priceSignalPolicy);
        this.deepBottomPolicy = Objects.requireNonNull(deepBottomPolicy);
        this.reversalPolicy = Objects.requireNonNull(reversalPolicy);
        this.technicalPolicy = Objects.requireNonNull(technicalPolicy);
        this.priceStructurePolicy = new PriceStructurePolicy();
    }

    public CompanyWalkForwardValidation evaluate(List<BottomPatternPoint> source) {
        var history = source == null ? List.<BottomPatternPoint>of() : source.stream()
                .sorted(Comparator.comparing(BottomPatternPoint::date))
                .toList();
        var specs = specs();
        var outcomes = new EnumMap<CompanyHorizon, List<Outcome>>(CompanyHorizon.class);
        var previousOn = new EnumMap<CompanyHorizon, Boolean>(CompanyHorizon.class);
        for (var spec : specs) {
            outcomes.put(spec.horizon(), new ArrayList<>());
            previousOn.put(spec.horizon(), false);
        }

        var minimumForward = specs.stream().mapToInt(Spec::days).min().orElse(0);
        for (var index = MINIMUM_HISTORY - 1; index + minimumForward < history.size(); index++) {
            var prefixStart = Math.max(0, index - FEATURE_LOOKBACK + 1);
            var prefix = history.subList(prefixStart, index + 1);
            var context = contextPolicy.evaluate(prefix);
            var price = priceSignalPolicy.evaluate(context);
            var deep = deepBottomPolicy.evaluate(context.toDeepBottomEvidence(price.failureRiskScore()));
            var technical = technicalPolicy.evaluate(prefix);
            var priceStructure = priceStructurePolicy.evaluate(prefix);
            var reversal = reversalPolicy.evaluate(new ReversalConfirmationEvidence(
                    deep,
                    technical.score(),
                    priceStructure.score(),
                    price.structureState(),
                    context.pattern().confirmPoint() == null ? null : context.pattern().confirmPoint().date(),
                    List.of(),
                    List.of(),
                    List.of()
            ));

            for (var spec : specs) {
                // Each horizon consumes every causally observable outcome.
                // Requiring the longest horizon here would incorrectly drop
                // the most recent 106 sessions from the 20-day validation.
                if (index + spec.days() >= history.size()) continue;
                var score = timingScore(
                        spec.horizon(),
                        price.priceBottomScore(),
                        deep.score(),
                        reversal.score(),
                        technical.state() == io.macrosquare.company.domain.bottom.VolumePriceConfirmationState.UNAVAILABLE
                                ? 50 : technical.score()
                );
                var on = score >= SIGNAL_THRESHOLD;
                if (on && !previousOn.get(spec.horizon())) {
                    outcomes.get(spec.horizon()).add(outcome(history, index, spec));
                }
                previousOn.put(spec.horizon(), on);
            }
        }

        var metrics = specs.stream()
                .map(spec -> metric(spec, outcomes.get(spec.horizon())))
                .toList();
        return new CompanyWalkForwardValidation(
                history.isEmpty() ? null : history.getFirst().date(),
                history.isEmpty() ? null : history.getLast().date(),
                history.size(),
                "당시까지의 가격·거래량만 사용한 확장형 워크포워드입니다. 현재 재무지표와 미래 수익률은 신호 계산에서 제외했습니다.",
                metrics
        );
    }

    private static int timingScore(
            CompanyHorizon horizon,
            int priceBottom,
            int deepBottom,
            int reversal,
            int technical
    ) {
        var score = switch (horizon) {
            case SHORT_TERM -> priceBottom * 0.20 + deepBottom * 0.20 + reversal * 0.30 + technical * 0.30;
            case SWING_TERM -> priceBottom * 0.25 + deepBottom * 0.25 + reversal * 0.25 + technical * 0.25;
            case LONG_TERM -> priceBottom * 0.30 + deepBottom * 0.30 + reversal * 0.20 + technical * 0.20;
        };
        return Math.max(0, Math.min(100, (int) Math.round(score)));
    }

    private static Outcome outcome(List<BottomPatternPoint> history, int index, Spec spec) {
        var entry = history.get(index).close();
        var exit = history.get(index + spec.days()).close();
        var forwardReturn = ((exit / entry) - 1) * 100;
        var targetIndex = (Integer) null;
        var minimumReturn = 0.0;
        for (var offset = 1; offset <= spec.days(); offset++) {
            var value = ((history.get(index + offset).close() / entry) - 1) * 100;
            minimumReturn = Math.min(minimumReturn, value);
            if (targetIndex == null && value >= spec.targetReturnPct()) targetIndex = offset;
        }
        return new Outcome(forwardReturn, minimumReturn, targetIndex);
    }

    private static HorizonWalkForwardMetric metric(Spec spec, List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return new HorizonWalkForwardMetric(
                    spec.horizon(), spec.days(), spec.targetReturnPct(), SIGNAL_THRESHOLD,
                    0, null, null, null, null, null, null
            );
        }
        var returns = outcomes.stream().map(Outcome::forwardReturnPct).sorted().toList();
        var positive = outcomes.stream().filter(value -> value.forwardReturnPct() > 0).count();
        var targets = outcomes.stream().filter(value -> value.daysToTarget() != null).toList();
        var median = returns.size() % 2 == 1
                ? returns.get(returns.size() / 2)
                : (returns.get(returns.size() / 2 - 1) + returns.get(returns.size() / 2)) / 2.0;
        return new HorizonWalkForwardMetric(
                spec.horizon(),
                spec.days(),
                spec.targetReturnPct(),
                SIGNAL_THRESHOLD,
                outcomes.size(),
                round(positive * 100.0 / outcomes.size()),
                round(targets.size() * 100.0 / outcomes.size()),
                round(outcomes.stream().mapToDouble(Outcome::forwardReturnPct).average().orElse(0)),
                round(median),
                targets.isEmpty() ? null : round(targets.stream().mapToInt(Outcome::daysToTarget).average().orElse(0)),
                round(outcomes.stream().mapToDouble(Outcome::maximumDrawdownPct).average().orElse(0))
        );
    }

    private static List<Spec> specs() {
        return List.of(
                new Spec(CompanyHorizon.SHORT_TERM, 20, 5),
                new Spec(CompanyHorizon.SWING_TERM, 63, 10),
                new Spec(CompanyHorizon.LONG_TERM, 126, 15)
        );
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Spec(CompanyHorizon horizon, int days, double targetReturnPct) {
    }

    private record Outcome(double forwardReturnPct, double maximumDrawdownPct, Integer daysToTarget) {
    }
}
