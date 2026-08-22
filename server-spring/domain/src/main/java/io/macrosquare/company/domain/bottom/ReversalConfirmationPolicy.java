package io.macrosquare.company.domain.bottom;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ReversalConfirmationPolicy {

    public ReversalConfirmation evaluate(ReversalConfirmationEvidence evidence) {
        var confirmed = evidence.confirmedBottom();
        var volumeScore = evidence.technicalConfirmationScore() == null ? 45 : evidence.technicalConfirmationScore();
        var priceScore = evidence.priceStructureScore() == null ? 45 : evidence.priceStructureScore();
        var bottomScore = confirmed == null ? 45 : confirmed.score();
        // Reversal confirmation is deliberately independent from the capitulation
        // score. Copying the deep-bottom score made a violent decline appear as a
        // STRONG reversal even when price structure and follow-through volume were weak.
        var score = roundScore(bottomScore * 0.45 + volumeScore * 0.30 + priceScore * 0.25);
        var conviction = confirmed != null && confirmed.state() == DeepBottomState.CONVICTION;
        var candidateOrConviction = confirmed != null
                && (confirmed.state() == DeepBottomState.CANDIDATE
                || confirmed.state() == DeepBottomState.CONVICTION);
        var structuralConfirmation = evidence.structureState() == BottomStructureState.STRUCTURAL_BOTTOM_POSSIBLE;
        var firstConfirmation = structuralConfirmation
                || evidence.structureState() == BottomStructureState.FIRST_CONFIRMATION;
        var hasConfirmationMarker = evidence.confirmMarkerDate() != null;

        var status = conviction && structuralConfirmation && hasConfirmationMarker
                && volumeScore >= 72 && priceScore >= 68 && score >= 78
                ? ReversalConfirmationStatus.STRONG
                : candidateOrConviction && firstConfirmation && hasConfirmationMarker
                        && volumeScore >= 62 && priceScore >= 60 && score >= 68
                ? ReversalConfirmationStatus.ON
                : confirmed != null && (confirmed.state() == DeepBottomState.CONVICTION
                        || confirmed.state() == DeepBottomState.CANDIDATE)
                        || evidence.structureState() == BottomStructureState.STRUCTURAL_BOTTOM_POSSIBLE
                        || evidence.structureState() == BottomStructureState.FIRST_CONFIRMATION
                ? ReversalConfirmationStatus.EARLY
                : ReversalConfirmationStatus.OFF;

        var signalDate = (status == ReversalConfirmationStatus.STRONG
                || status == ReversalConfirmationStatus.ON) && evidence.confirmMarkerDate() != null
                ? evidence.confirmMarkerDate()
                : confirmed != null && confirmed.signalDate() != null
                ? confirmed.signalDate()
                : evidence.confirmMarkerDate();

        var summary = switch (status) {
            case STRONG -> "바닥 신호와 독립된 가격 구조·거래량 확인이 모두 붙어 강한 반전 확인 상태입니다.";
            case ON -> "가격의 1차 반전과 거래량 조건이 확인됐습니다. 추격보다 분할 기준이 필요한 구간입니다.";
            case EARLY -> "바닥 후보는 형성됐지만 가격 구조나 거래량 후속 확인이 부족해 반전 초기 단계입니다.";
            case OFF -> "반전 확인 신호는 아직 꺼져 있습니다. 가격 반등만으로 확신하기는 이릅니다.";
        };

        var reasons = new LinkedHashSet<String>();
        if (hasConfirmationMarker && firstConfirmation) {
            reasons.add("저점 이후 가격의 1차 반전 구조가 확인됐습니다.");
        }
        if (volumeScore >= 72) {
            reasons.add("독립 OBV/VWAP 확인 점수 " + volumeScore + "/100으로 강합니다.");
        } else if (volumeScore >= 62) {
            reasons.add("독립 OBV/VWAP 확인 점수 " + volumeScore + "/100으로 기준을 충족했습니다.");
        }
        if (confirmed != null) reasons.addAll(confirmed.reasons());
        else reasons.addAll(evidence.bottomReasons());

        var cautions = new LinkedHashSet<String>();
        if (!firstConfirmation || !hasConfirmationMarker) {
            cautions.add("저점 이후 독립된 가격 확인 구조가 아직 완성되지 않았습니다.");
        }
        if (volumeScore < 62) {
            cautions.add("독립 OBV/VWAP 확인 점수가 " + volumeScore + "/100으로 부족합니다.");
        }
        if (priceScore < 60) {
            cautions.add("독립 가격 구조 점수가 " + priceScore + "/100으로 반전 확정 기준에 못 미칩니다.");
        }
        if (confirmed != null) cautions.addAll(confirmed.cautions());
        else cautions.addAll(evidence.bottomCautions());
        if (evidence.failureSignals() != null) cautions.addAll(evidence.failureSignals());

        return new ReversalConfirmation(
                status,
                score,
                signalDate,
                summary,
                firstFour(new ArrayList<>(reasons)),
                firstFour(new ArrayList<>(cautions))
        );
    }

    private static int roundScore(double value) {
        return (int) Math.round(Math.min(100, Math.max(0, value)));
    }

    private static List<String> firstFour(List<String> values) {
        return values.stream().limit(4).toList();
    }
}
