package io.macrosquare.research.domain.rotation;

import java.util.ArrayList;

/**
 * Separates a high relative rank from an actually confirmed leadership hand-off.
 * The result is a weighted condition score, not a forward return probability.
 */
public final class SectorLeadershipConfirmationPolicy {

    public SectorLeadershipConfirmation evaluate(SectorLeadershipConfirmationEvidence evidence) {
        var weighted = evidence.rotationScore() * 0.20
                + evidence.macroFitScore() * 0.15
                + evidence.mediumTermRelativeStrengthScore() * 0.25
                + momentumScore(evidence.shortTermRelativeStrengthPct()) * 0.10;
        var availableWeight = 70;
        if (evidence.earningsRevisionScore() != null) {
            weighted += evidence.earningsRevisionScore() * 0.15;
            availableWeight += 15;
        }
        if (evidence.flowScore() != null) {
            weighted += evidence.flowScore() * 0.15;
            availableWeight += 15;
        }
        var score = clamp((int) Math.round(weighted / availableWeight * 100));
        if (evidence.crowdingReliefScore() < 25) score = Math.max(0, score - 10);
        var coverage = availableWeight;

        var currentInvalidation = currentInvalidation(evidence);
        var currentMomentumConfirmed = positive(evidence.shortTermRelativeStrengthPct())
                && positive(evidence.mediumTermRelativeStrengthPct());
        var hasIndependentConfirmation = evidence.earningsRevisionScore() != null
                || evidence.flowScore() != null;
        var state = currentInvalidation
                ? SectorLeadershipConfirmation.State.INVALIDATED
                : coverage >= 85 && score >= 70 && currentMomentumConfirmed
                && evidence.earningsRevisionScore() != null
                && evidence.flowScore() != null
                && (evidence.rotationState() == SectorRotationState.LEADING
                || evidence.rotationState() == SectorRotationState.IMPROVING)
                ? SectorLeadershipConfirmation.State.CONFIRMED
                : hasIndependentConfirmation && coverage >= 70 && score >= 60
                && evidence.rotationState() != SectorRotationState.LAGGING
                ? SectorLeadershipConfirmation.State.BUILDING
                : SectorLeadershipConfirmation.State.WATCH;

        var reasons = reasons(evidence, currentMomentumConfirmed);
        return new SectorLeadershipConfirmation(
                state, score, coverage, label(state), reasons,
                invalidationSignals(evidence));
    }

    private static boolean currentInvalidation(SectorLeadershipConfirmationEvidence evidence) {
        var momentumBroken = evidence.mediumTermRelativeStrengthPct() != null
                && evidence.mediumTermRelativeStrengthPct() <= -3
                && evidence.shortTermRelativeStrengthPct() != null
                && evidence.shortTermRelativeStrengthPct() <= 0;
        var estimatesBroken = evidence.earningsRevisionScore() != null
                && evidence.earningsRevisionScore() <= 40;
        var rankBroken = evidence.rotationState() == SectorRotationState.LAGGING
                && evidence.rotationScore() < 55;
        return momentumBroken || estimatesBroken || rankBroken;
    }

    private static ArrayList<String> reasons(
            SectorLeadershipConfirmationEvidence evidence,
            boolean currentMomentumConfirmed
    ) {
        var result = new ArrayList<String>();
        result.add(currentMomentumConfirmed
                ? "단기·중기 상대강도가 모두 플러스입니다."
                : "단기·중기 상대강도 동시 확인이 아직 부족합니다.");
        result.add(evidence.macroFitScore() >= 65
                ? "현재 거시 국면 정합이 확인됩니다."
                : "거시 국면 정합이 아직 약합니다.");
        if (evidence.earningsRevisionScore() == null) {
            result.add("섹터 이익추정 변화가 없어 확인도에서 제외했습니다.");
        } else if (evidence.earningsRevisionScore() >= 52) {
            result.add("이익추정 변화가 중립 이상입니다.");
        } else {
            result.add("이익추정 변화가 가격 모멘텀을 따라오지 못합니다.");
        }
        if (evidence.flowScore() == null) {
            result.add("현재 사용 가능한 공식 ETF 생성·환매가 없습니다.");
        } else if (evidence.flowScore() >= 52) {
            result.add("공식 ETF 생성·환매 흐름이 중립 이상입니다.");
        } else {
            result.add("공식 ETF 생성·환매 흐름이 아직 약합니다.");
        }
        if (evidence.crowdingReliefScore() < 25) {
            result.add("혼잡도가 높아 확인 점수와 별도로 추격을 제한합니다.");
        }
        return result;
    }

    private static java.util.List<String> invalidationSignals(
            SectorLeadershipConfirmationEvidence evidence
    ) {
        var result = new ArrayList<String>();
        result.add("중기 상대강도가 -3% 이하이고 단기 상대강도도 음수로 전환");
        result.add("이익추정 점수가 40 이하로 하락");
        result.add("순환 점수가 55 아래로 내려가며 LAGGING 전환");
        if (evidence.crowdingReliefScore() < 35) {
            result.add("높은 혼잡도에서 추가 급등 후 상대강도 둔화");
        }
        return java.util.List.copyOf(result);
    }

    private static int momentumScore(Double value) {
        if (value == null) return 0;
        return clamp((int) Math.round(50 + value * 5));
    }

    private static boolean positive(Double value) {
        return value != null && value > 0;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String label(SectorLeadershipConfirmation.State state) {
        return switch (state) {
            case CONFIRMED -> "주도 전환 확인";
            case BUILDING -> "확인 진행 중";
            case WATCH -> "관찰 단계";
            case INVALIDATED -> "현재 조건 훼손";
        };
    }
}
