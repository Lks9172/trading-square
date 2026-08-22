package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * No-lookahead monthly walk-forward validation of the live sector-momentum layer.
 *
 * <p>The current score is deliberately pre-specified rather than optimized over the
 * requested test window: equal-weight six- and twelve-month relative total return,
 * both ending one month before formation, divided by trailing one-year relative
 * volatility. The recent-month exclusion and risk adjustment follow the broad
 * construction principles used by institutional momentum indexes. The previous
 * 1/3/6/12-month score remains in the same result as a like-for-like comparison
 * baseline, never as the live methodology.</p>
 */
public final class SectorWalkForwardBacktestPolicy {

    public static final String METHODOLOGY_VERSION =
            "CURRENT_TOTAL_RETURN_RISK_ADJUSTED_MOMENTUM_WALK_FORWARD_V2";
    public static final String COMPARISON_BASELINE_VERSION =
            "CURRENT_TOTAL_RETURN_RS_WALK_FORWARD_V1_COMPARISON_ONLY";
    public static final String BENCHMARK_KEY = "SPY_TR";
    public static final List<String> SECTOR_KEYS = List.of(
            "XLK_TR", "XLF_TR", "XLE_TR", "XLV_TR", "XLI_TR", "XLY_TR", "XLC_TR", "XLB_TR",
            "XLRE_TR", "XLU_TR", "XLP_TR"
    );
    private static final int RECENT_MONTH_POINTS = 21;
    private static final int SIX_MONTH_FORMATION_POINTS = 126;
    private static final int TWELVE_MONTH_FORMATION_POINTS = 252;
    private static final int VOLATILITY_POINTS = 252;
    private static final List<Horizon> HORIZONS = List.of(
            new Horizon("oneMonth", 1, 21),
            new Horizon("threeMonth", 3, 63),
            new Horizon("sixMonth", 6, 126)
    );

    public SectorWalkForwardBacktest evaluate(
            Map<String, List<SectorTotalReturnPoint>> input,
            LocalDate asOf,
            int years
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(asOf, "asOf");
        if (years < 1 || years > 10) throw new IllegalArgumentException("years must be between 1 and 10");

        var histories = normalized(input, asOf);
        var benchmark = histories.getOrDefault(BENCHMARK_KEY, List.of());
        if (benchmark.size() < 379) {
            throw new IllegalArgumentException("SPY total-return history is insufficient");
        }
        for (var key : SECTOR_KEYS) {
            if (histories.getOrDefault(key, List.of()).size() < 379) {
                throw new IllegalArgumentException(key + " total-return history is insufficient");
            }
        }

        var valueByKeyAndDate = valuesByDate(histories);
        var benchmarkDates = benchmark.stream().map(SectorTotalReturnPoint::date).toList();
        var start = asOf.minusYears(years);
        var currentMonth = YearMonth.from(asOf);
        var signalDates = monthEnds(benchmarkDates).stream()
                .filter(date -> !date.isBefore(start) && YearMonth.from(date).isBefore(currentMonth))
                .toList();
        var events = events(histories, valueByKeyAndDate, benchmarkDates, signalDates, RankingMethod.CURRENT);
        var baselineEvents = events(
                histories, valueByKeyAndDate, benchmarkDates, signalDates, RankingMethod.V1_BASELINE);
        if (events.isEmpty()) throw new IllegalArgumentException("walk-forward signal history is empty");

        return new SectorWalkForwardBacktest(
                METHODOLOGY_VERSION,
                events.getFirst().signalDate(),
                events.getLast().signalDate(),
                events.size(),
                summarize(events),
                summarize(baselineEvents),
                round(averageMonthlyTurnover(events)),
                events
        );
    }

    private static List<SectorWalkForwardBacktest.SignalEvent> events(
            Map<String, List<SectorTotalReturnPoint>> histories,
            Map<String, Map<LocalDate, Double>> valueByKeyAndDate,
            List<LocalDate> benchmarkDates,
            List<LocalDate> signalDates,
            RankingMethod method
    ) {
        var events = new ArrayList<SectorWalkForwardBacktest.SignalEvent>();
        for (var signalDate : signalDates) {
            var ranks = rank(histories, signalDate, method);
            if (ranks.size() < 3) continue;
            var top3 = ranks.subList(0, 3).stream().map(Rank::key).toList();
            var signalIndex = java.util.Collections.binarySearch(benchmarkDates, signalDate);
            if (signalIndex < 0) continue;
            var forward = new LinkedHashMap<String, SectorWalkForwardBacktest.ForwardResult>();
            for (var horizon : HORIZONS) {
                var targetIndex = signalIndex + horizon.tradingPoints();
                if (targetIndex >= benchmarkDates.size()) continue;
                var result = forwardResult(signalDate, benchmarkDates.get(targetIndex), top3, valueByKeyAndDate);
                if (result != null) forward.put(horizon.key(), result);
            }
            // A requested period can predate XLC's common history or the latest
            // completed month can still lack even a one-month outcome. Such rows
            // are not measured rebalances and must not inflate rebalance count,
            // turnover, or the reported test range.
            if (forward.isEmpty()) continue;
            events.add(new SectorWalkForwardBacktest.SignalEvent(
                    signalDate, top3.getFirst(), top3, forward));
        }
        return List.copyOf(events);
    }

    private static Map<String, List<SectorTotalReturnPoint>> normalized(
            Map<String, List<SectorTotalReturnPoint>> input,
            LocalDate asOf
    ) {
        var result = new LinkedHashMap<String, List<SectorTotalReturnPoint>>();
        var keys = new ArrayList<String>();
        keys.add(BENCHMARK_KEY);
        keys.addAll(SECTOR_KEYS);
        for (var key : keys) {
            var byDate = new LinkedHashMap<LocalDate, SectorTotalReturnPoint>();
            for (var point : input.getOrDefault(key, List.of())) {
                if (point != null && !point.date().isAfter(asOf)) byDate.put(point.date(), point);
            }
            result.put(key, byDate.values().stream()
                    .sorted(Comparator.comparing(SectorTotalReturnPoint::date)).toList());
        }
        return Map.copyOf(result);
    }

    private static Map<String, Map<LocalDate, Double>> valuesByDate(
            Map<String, List<SectorTotalReturnPoint>> histories
    ) {
        var result = new LinkedHashMap<String, Map<LocalDate, Double>>();
        histories.forEach((key, points) -> {
            var values = new LinkedHashMap<LocalDate, Double>();
            points.forEach(point -> values.put(point.date(), point.value()));
            result.put(key, Map.copyOf(values));
        });
        return Map.copyOf(result);
    }

    private static List<LocalDate> monthEnds(List<LocalDate> dates) {
        var result = new LinkedHashMap<YearMonth, LocalDate>();
        dates.forEach(date -> result.put(YearMonth.from(date), date));
        return List.copyOf(result.values());
    }

    private static List<Rank> rank(
            Map<String, List<SectorTotalReturnPoint>> histories,
            LocalDate signalDate,
            RankingMethod method
    ) {
        var benchmark = histories.get(BENCHMARK_KEY);
        var result = new ArrayList<Rank>();
        for (var key : SECTOR_KEYS) {
            var sector = histories.get(key);
            Double score;
            if (method == RankingMethod.CURRENT) {
                var relativeSix = relativeReturn(
                        sector, benchmark, signalDate,
                        RECENT_MONTH_POINTS + SIX_MONTH_FORMATION_POINTS, RECENT_MONTH_POINTS);
                var relativeTwelve = relativeReturn(
                        sector, benchmark, signalDate,
                        RECENT_MONTH_POINTS + TWELVE_MONTH_FORMATION_POINTS, RECENT_MONTH_POINTS);
                var volatility = relativeVolatility(
                        sector, benchmark, signalDate, VOLATILITY_POINTS, RECENT_MONTH_POINTS);
                score = relativeSix == null || relativeTwelve == null || volatility == null
                        ? null
                        : (relativeSix * .5 + relativeTwelve * .5) / Math.max(volatility, .0001);
            } else {
                var relative1 = relativeReturn(sector, benchmark, signalDate, 21, 0);
                var relative3 = relativeReturn(sector, benchmark, signalDate, 63, 0);
                var relative6 = relativeReturn(sector, benchmark, signalDate, 126, 0);
                var relative12 = relativeReturn(sector, benchmark, signalDate, 252, 0);
                score = relative1 == null || relative3 == null || relative6 == null || relative12 == null
                        ? null
                        : relative1 * .15 + relative3 * .35 + relative6 * .35 + relative12 * .15;
            }
            if (score != null && Double.isFinite(score)) result.add(new Rank(key, score));
        }
        result.sort(Comparator.comparingDouble(Rank::score).reversed().thenComparing(Rank::key));
        return List.copyOf(result);
    }

    /** Return of the date-aligned sector/SPY ratio from {@code startLookback} to {@code endLag}. */
    private static Double relativeReturn(
            List<SectorTotalReturnPoint> sector,
            List<SectorTotalReturnPoint> benchmark,
            LocalDate signalDate,
            int startLookback,
            int endLag
    ) {
        var ratios = alignedRatios(sector, benchmark, signalDate);
        if (ratios.size() <= startLookback || startLookback <= endLag) return null;
        var end = ratios.get(ratios.size() - 1 - endLag);
        var start = ratios.get(ratios.size() - 1 - startLookback);
        return start <= 0 ? null : (end / start - 1) * 100d;
    }

    /** Annualized daily volatility of log changes in the sector/SPY total-return ratio. */
    private static Double relativeVolatility(
            List<SectorTotalReturnPoint> sector,
            List<SectorTotalReturnPoint> benchmark,
            LocalDate signalDate,
            int window,
            int endLag
    ) {
        var ratios = alignedRatios(sector, benchmark, signalDate);
        var end = ratios.size() - endLag;
        var start = end - window;
        if (start < 1 || end > ratios.size()) return null;
        var returns = new ArrayList<Double>(window);
        for (var index = start; index < end; index++) {
            var prior = ratios.get(index - 1);
            var current = ratios.get(index);
            if (prior <= 0 || current <= 0) return null;
            returns.add(Math.log(current / prior));
        }
        var mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(252d);
    }

    private static List<Double> alignedRatios(
            List<SectorTotalReturnPoint> sector,
            List<SectorTotalReturnPoint> benchmark,
            LocalDate signalDate
    ) {
        var benchmarkByDate = new LinkedHashMap<LocalDate, Double>();
        benchmark.stream().filter(point -> !point.date().isAfter(signalDate))
                .forEach(point -> benchmarkByDate.put(point.date(), point.value()));
        var ratios = new ArrayList<Double>();
        sector.stream().filter(point -> !point.date().isAfter(signalDate)).forEach(point -> {
            var benchmarkValue = benchmarkByDate.get(point.date());
            if (benchmarkValue != null && benchmarkValue > 0 && point.value() > 0) {
                ratios.add(point.value() / benchmarkValue);
            }
        });
        return List.copyOf(ratios);
    }

    private static SectorWalkForwardBacktest.ForwardResult forwardResult(
            LocalDate from,
            LocalDate to,
            List<String> top3,
            Map<String, Map<LocalDate, Double>> values
    ) {
        var benchmark = returnPct(values.get(BENCHMARK_KEY), from, to);
        if (benchmark == null) return null;
        var universeReturns = new ArrayList<Double>();
        for (var key : SECTOR_KEYS) {
            var value = returnPct(values.get(key), from, to);
            if (value == null) return null;
            universeReturns.add(value);
        }
        var returns = new ArrayList<Double>();
        for (var key : top3) {
            var value = returnPct(values.get(key), from, to);
            if (value == null) return null;
            returns.add(value);
        }
        var universe = average(universeReturns);
        var top1 = returns.getFirst();
        var top3Average = average(returns);
        return new SectorWalkForwardBacktest.ForwardResult(
                benchmark, universe, top1, top3Average,
                top1 - benchmark, top3Average - benchmark,
                top1 - universe, top3Average - universe);
    }

    private static Double returnPct(Map<LocalDate, Double> values, LocalDate from, LocalDate to) {
        if (values == null) return null;
        var start = values.get(from);
        var end = values.get(to);
        if (start == null || end == null || start <= 0) return null;
        return (end / start - 1) * 100d;
    }

    private static Map<String, SectorWalkForwardBacktest.HorizonResult> summarize(
            List<SectorWalkForwardBacktest.SignalEvent> events
    ) {
        var summaries = new LinkedHashMap<String, SectorWalkForwardBacktest.HorizonResult>();
        for (var horizon : HORIZONS) summaries.put(horizon.key(), summarize(horizon, events));
        return Map.copyOf(summaries);
    }

    private static SectorWalkForwardBacktest.HorizonResult summarize(
            Horizon horizon,
            List<SectorWalkForwardBacktest.SignalEvent> events
    ) {
        var values = events.stream().map(event -> event.forward().get(horizon.key()))
                .filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return new SectorWalkForwardBacktest.HorizonResult(
                    horizon.months(), 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0,
                    Math.max(0, horizon.months() - 1), 0, 0);
        }
        var top1 = values.stream().map(SectorWalkForwardBacktest.ForwardResult::top1ExcessPct).toList();
        var top3 = values.stream().map(SectorWalkForwardBacktest.ForwardResult::top3ExcessPct).toList();
        var top1Universe = values.stream()
                .map(SectorWalkForwardBacktest.ForwardResult::top1UniverseExcessPct).toList();
        var top3Universe = values.stream()
                .map(SectorWalkForwardBacktest.ForwardResult::top3UniverseExcessPct).toList();
        var interval = wilsonInterval(top1);
        var overlapAdjustedInterval = overlapAdjustedInterval(top1, horizon.months(), interval);
        return new SectorWalkForwardBacktest.HorizonResult(
                horizon.months(), values.size(),
                round(ratePositive(top1)), round(ratePositive(top3)),
                round(average(top1)), round(average(top3)),
                round(median(top1)), round(median(top3)),
                round(ratePositive(values.stream()
                        .map(SectorWalkForwardBacktest.ForwardResult::top1ReturnPct).toList())),
                round(ratePositive(values.stream()
                        .map(SectorWalkForwardBacktest.ForwardResult::top3ReturnPct).toList())),
                round(ratePositive(top1Universe)), round(ratePositive(top3Universe)),
                round(average(top1Universe)), round(average(top3Universe)),
                round(interval.lowerPct()), round(interval.upperPct()),
                Math.max(0, horizon.months() - 1),
                round(overlapAdjustedInterval.lowerPct()), round(overlapAdjustedInterval.upperPct())
        );
    }

    private static double averageMonthlyTurnover(List<SectorWalkForwardBacktest.SignalEvent> events) {
        if (events.size() < 2) return 0;
        double total = 0;
        for (var index = 1; index < events.size(); index++) {
            var previous = events.get(index - 1).top3();
            var current = events.get(index).top3();
            var retained = current.stream().filter(previous::contains).count();
            total += 1d - retained / 3d;
        }
        return total * 100d / (events.size() - 1);
    }

    private static ConfidenceInterval wilsonInterval(List<Double> values) {
        if (values.isEmpty()) return new ConfidenceInterval(0, 0);
        var successes = values.stream().filter(value -> value > 0).count();
        var n = values.size();
        var z = 1.959963984540054;
        var z2 = z * z;
        var probability = successes / (double) n;
        var denominator = 1 + z2 / n;
        var center = (probability + z2 / (2 * n)) / denominator;
        var margin = z * Math.sqrt((probability * (1 - probability) + z2 / (4 * n)) / n) / denominator;
        return new ConfidenceInterval(
                Math.max(0, center - margin) * 100,
                Math.min(1, center + margin) * 100);
    }

    /**
     * Newey-West/Bartlett adjustment for overlapping monthly forward windows.
     * The result is never allowed to be narrower than the ordinary Wilson
     * interval, which remains useful for small Bernoulli samples.
     */
    private static ConfidenceInterval overlapAdjustedInterval(
            List<Double> excessReturns,
            int horizonMonths,
            ConfidenceInterval wilson
    ) {
        if (excessReturns.isEmpty()) return new ConfidenceInterval(0, 0);
        var observations = excessReturns.stream().map(value -> value > 0 ? 1d : 0d).toList();
        var n = observations.size();
        var mean = average(observations);
        var lagLimit = Math.min(Math.max(0, horizonMonths - 1), n - 1);
        double longRunVariance = 0;
        for (var value : observations) longRunVariance += Math.pow(value - mean, 2);
        longRunVariance /= n;
        for (var lag = 1; lag <= lagLimit; lag++) {
            double covariance = 0;
            for (var index = lag; index < n; index++) {
                covariance += (observations.get(index) - mean)
                        * (observations.get(index - lag) - mean);
            }
            covariance /= n;
            var weight = 1d - lag / (double) (lagLimit + 1);
            longRunVariance += 2d * weight * covariance;
        }
        var standardError = Math.sqrt(Math.max(0, longRunVariance) / n);
        var normalLower = Math.max(0, mean - 1.959963984540054 * standardError) * 100;
        var normalUpper = Math.min(1, mean + 1.959963984540054 * standardError) * 100;
        return new ConfidenceInterval(
                Math.min(wilson.lowerPct(), normalLower),
                Math.max(wilson.upperPct(), normalUpper));
    }

    private static double ratePositive(List<Double> values) {
        return values.stream().filter(value -> value > 0).count() * 100d / values.size();
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    private static double median(List<Double> input) {
        var values = input.stream().sorted().toList();
        var middle = values.size() / 2;
        return values.size() % 2 == 1
                ? values.get(middle)
                : (values.get(middle - 1) + values.get(middle)) / 2d;
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private enum RankingMethod { CURRENT, V1_BASELINE }

    private record Horizon(String key, int months, int tradingPoints) {
    }

    private record Rank(String key, double score) {
    }

    private record ConfidenceInterval(double lowerPct, double upperPct) {
    }
}
