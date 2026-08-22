package io.macrosquare.company.domain.horizon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit, reviewable horizon weights instead of one score reused at every time frame. */
public final class CompanyHorizonSignalPolicy {

    public static final CompanyHorizonWeights SHORT_WEIGHTS =
            new CompanyHorizonWeights(5, 5, 3, 2, 3, 20, 15, 22, 25);
    public static final CompanyHorizonWeights SWING_WEIGHTS =
            new CompanyHorizonWeights(10, 10, 8, 7, 6, 20, 14, 12, 13);
    public static final CompanyHorizonWeights LONG_WEIGHTS =
            new CompanyHorizonWeights(15, 20, 15, 18, 12, 12, 3, 2, 3);

    public CompanyHorizonView evaluate(CompanyHorizonEvidence evidence) {
        return new CompanyHorizonView(
                signal(CompanyHorizon.SHORT_TERM, evidence, SHORT_WEIGHTS),
                signal(CompanyHorizon.SWING_TERM, evidence, SWING_WEIGHTS),
                signal(CompanyHorizon.LONG_TERM, evidence, LONG_WEIGHTS)
        );
    }

    private static CompanyHorizonSignal signal(
            CompanyHorizon horizon,
            CompanyHorizonEvidence evidence,
            CompanyHorizonWeights weights
    ) {
        var components = components(evidence, weights);
        var weightedScore = components.stream().mapToDouble(Component::contribution).sum();
        var score = clamp((int) Math.round(weightedScore));
        var confidence = clamp(components.stream()
                .filter(Component::available)
                .mapToInt(Component::weight)
                .sum());
        // A neutral placeholder score must never become a reduce/sell call
        // when the underlying evidence is mostly absent.
        var action = confidence < 40 ? CompanyHorizonAction.HOLD : action(score);
        var reasons = components.stream()
                .filter(Component::available)
                .sorted(Comparator.comparingDouble(Component::impact).reversed())
                .limit(3)
                .map(component -> component.label() + " " + component.value() + "점")
                .toList();
        return new CompanyHorizonSignal(
                horizon,
                score,
                action,
                confidence,
                weights,
                summary(horizon, action, confidence),
                reasons
        );
    }

    private static List<Component> components(CompanyHorizonEvidence value, CompanyHorizonWeights weights) {
        var result = new ArrayList<Component>();
        result.add(component("기업 총점", value.companyScore(), weights.company()));
        result.add(component("수익성", value.qualityScore(), weights.quality()));
        result.add(component("성장", value.growthScore(), weights.growth()));
        result.add(component("밸류", value.valuationScore(), weights.valuation()));
        result.add(component("재무", value.balanceSheetScore(), weights.balanceSheet()));
        result.add(component("Buy Score", value.buyScore(), weights.buy()));
        result.add(component("바닥", value.bottomScore(), weights.bottom()));
        result.add(component("반전", value.reversalScore(), weights.reversal()));
        result.add(component("OBV/VWAP", value.technicalScore(), weights.technical()));
        return List.copyOf(result);
    }

    private static Component component(String label, Integer value, int weight) {
        return new Component(label, value == null ? 50 : value, weight, value != null);
    }

    private static CompanyHorizonAction action(int score) {
        if (score >= 80) return CompanyHorizonAction.STRONG_BUY;
        if (score >= 70) return CompanyHorizonAction.BUY;
        if (score >= 55) return CompanyHorizonAction.HOLD;
        if (score >= 40) return CompanyHorizonAction.REDUCE;
        return CompanyHorizonAction.SELL;
    }

    private static String summary(CompanyHorizon horizon, CompanyHorizonAction action, int confidence) {
        var frame = switch (horizon) {
            case SHORT_TERM -> "단기는 가격·반전·수급 확인을 가장 크게 반영했습니다.";
            case SWING_TERM -> "스윙은 타이밍과 기업 체력을 균형 있게 반영했습니다.";
            case LONG_TERM -> "장기는 수익성·성장·밸류·재무를 가장 크게 반영했습니다.";
        };
        var actionText = switch (action) {
            case STRONG_BUY -> "강한 매수 우호";
            case BUY -> "매수 우호";
            case HOLD -> "관찰·보유";
            case REDUCE -> "비중 축소";
            case SELL -> "회피·매도";
        };
        return frame + " 현재는 " + actionText + "이며 데이터 충족도는 " + confidence + "%입니다.";
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record Component(String label, int value, int weight, boolean available) {
        private double contribution() {
            return value * weight / 100.0;
        }

        private double impact() {
            return Math.abs(value - 50) * weight;
        }
    }
}
