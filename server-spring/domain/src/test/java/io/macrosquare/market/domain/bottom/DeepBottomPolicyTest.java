package io.macrosquare.company.domain.bottom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepBottomPolicyTest {

    private final DeepBottomPolicy policy = new DeepBottomPolicy();
    private final ReversalConfirmationPolicy reversalPolicy = new ReversalConfirmationPolicy();

    @Test
    void matchesTheCurrentNodeNvdaUnmetGoldenMaster() {
        var signal = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 6, 26),
                1.18,
                1.17,
                1.0,
                -14.0,
                0.3,
                -3.8,
                true,
                14,
                5.3,
                52
        ));

        assertEquals(56, signal.score());
        assertEquals(DeepBottomState.UNMET, signal.state());
        assertEquals(BottomActionBias.WAIT, signal.actionBias());
        assertEquals(1.17, signal.recentVolumeRatio());
        assertEquals(List.of("직전 3개 거래일 최대 대비 거래량 1.17배로 투매 흡수 흔적"), signal.reasons());
        assertEquals(List.of(
                "낙폭 축소가 충분하지 않아 흡수 신호가 약합니다.",
                "20일선 이격이 작아 강한 투매성 바닥으로 보기 어렵습니다."
        ), signal.cautions());

        var reversal = reversalPolicy.evaluate(reversalEvidence(signal, BottomStructureState.BOTTOM_ATTEMPT));
        assertEquals(ReversalConfirmationStatus.OFF, reversal.status());
        assertEquals(55, reversal.score());
        assertEquals(LocalDate.of(2026, 6, 26), reversal.signalDate());
    }

    @Test
    void matchesTheCurrentNodeIsrgConvictionGoldenMaster() {
        var signal = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                2.04,
                2.0,
                1.0,
                -34.7,
                -14.4,
                -9.0,
                true,
                0,
                0.0,
                44
        ));

        assertEquals(100, signal.score());
        assertEquals(DeepBottomState.CONVICTION, signal.state());
        assertEquals(BottomActionBias.SCALE_IN_BUY, signal.actionBias());
        assertEquals(List.of(
                "직전 3개 거래일 최대 대비 거래량 2배로 투매 흡수 흔적",
                "직전 3일 누적 하락 -9%로 급락 구간 통과",
                "20일선 대비 -14.4% 이격으로 과매도 구간",
                "120일 고점 대비 -34.7% 하락"
        ), signal.reasons());

        var reversal = reversalPolicy.evaluate(reversalEvidence(signal, BottomStructureState.BOTTOM_ATTEMPT));
        assertEquals(ReversalConfirmationStatus.EARLY, reversal.status());
        assertEquals(75, reversal.score());
        assertEquals("저점 이후 독립된 가격 확인 구조가 아직 완성되지 않았습니다.",
                reversal.cautions().getFirst());
    }

    @Test
    void matchesTheCurrentNodeNemCandidateGoldenMaster() {
        var signal = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                0.99,
                0.98,
                1.0,
                -32.0,
                -5.9,
                -5.3,
                true,
                0,
                0.0,
                62
        ));

        assertEquals(62, signal.score());
        assertEquals(DeepBottomState.CANDIDATE, signal.state());
        assertEquals(BottomActionBias.OBSERVE_BUY, signal.actionBias());
        assertEquals(List.of(
                "직전 3일 누적 하락 -5.3%로 급락 구간 통과",
                "120일 고점 대비 -32% 하락"
        ), signal.reasons());

        var reversal = reversalPolicy.evaluate(reversalEvidence(signal, BottomStructureState.BOTTOM_ATTEMPT));
        assertEquals(ReversalConfirmationStatus.EARLY, reversal.status());
        assertEquals(57, reversal.score());
    }

    @Test
    void candidateBottomCanReachOnOnlyWithIndependentPriceAndVolumeConfirmation() {
        var candidate = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                0.99, 0.98, 1.0, -32.0, -5.9, -5.3, true, 0, 0.0, 62
        ));
        var confirmDate = LocalDate.of(2026, 7, 24);

        var reversal = reversalPolicy.evaluate(new ReversalConfirmationEvidence(
                candidate, 85, 75, BottomStructureState.FIRST_CONFIRMATION,
                confirmDate, List.of(), List.of(), List.of()
        ));

        assertEquals(DeepBottomState.CANDIDATE, candidate.state());
        assertEquals(ReversalConfirmationStatus.ON, reversal.status());
        assertEquals(72, reversal.score());
        assertEquals(confirmDate, reversal.signalDate());
    }

    @Test
    void fallsBackToVolumeAndPriceScoresWhenDeepBottomEvidenceIsUnavailable() {
        var reversal = reversalPolicy.evaluate(new ReversalConfirmationEvidence(
                null,
                64,
                60,
                BottomStructureState.FIRST_CONFIRMATION,
                LocalDate.of(2026, 7, 1),
                List.of("1차 확인"),
                List.of("주의"),
                List.of()
        ));

        assertEquals(54, reversal.score());
        assertEquals(ReversalConfirmationStatus.EARLY, reversal.status());
        assertEquals(List.of(
                "저점 이후 가격의 1차 반전 구조가 확인됐습니다.",
                "독립 OBV/VWAP 확인 점수 64/100으로 기준을 충족했습니다.",
                "1차 확인"
        ), reversal.reasons());
        assertEquals(List.of("주의"), reversal.cautions());
    }

    @Test
    void requiresIndependentPriceStructureAndFollowThroughVolumeForStrongReversal() {
        var bottom = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                2.04, 2.0, 0.7, -34.7, -14.4, -9.0, true, 0, 0.0, 44
        ));
        var confirmDate = LocalDate.of(2026, 7, 23);

        var reversal = reversalPolicy.evaluate(new ReversalConfirmationEvidence(
                bottom, 76, 72, BottomStructureState.STRUCTURAL_BOTTOM_POSSIBLE,
                confirmDate, List.of(), List.of(), List.of()
        ));

        assertEquals(ReversalConfirmationStatus.STRONG, reversal.status());
        assertEquals(86, reversal.score());
        assertEquals(confirmDate, reversal.signalDate());
    }

    @Test
    void highDrawdownWithoutRelativeVolumeCannotBeConviction() {
        var signal = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                1.0, 0.95, 0.6, -40.0, -15.0, -12.0, true, 0, 0.0, 20
        ));

        assertEquals(DeepBottomState.CANDIDATE, signal.state());
    }

    @Test
    void requiresTheAbsorptionVolumeToBeatAllThreePreviousSessions() {
        var signal = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                1.20, 0.95, 0.6, -40.0, -15.0, -12.0, true, 0, 0.0, 20
        ));

        assertEquals(0.95, signal.recentVolumeRatio());
        assertEquals(DeepBottomState.CANDIDATE, signal.state());
        assertEquals(BottomActionBias.OBSERVE_BUY, signal.actionBias());
    }

    @Test
    void requiresAnActualSelloffDayForConviction() {
        var signal = policy.evaluate(new DeepBottomEvidence(
                LocalDate.of(2026, 7, 17),
                1.50, 1.40, null, -40.0, -15.0, -12.0, true, 0, 0.0, 20
        ));

        assertEquals(DeepBottomState.CANDIDATE, signal.state());
        assertEquals(BottomActionBias.OBSERVE_BUY, signal.actionBias());
        assertTrue(signal.cautions().contains("하락일 투매 흡수 조건이 확인되지 않았습니다."));
    }

    private static ReversalConfirmationEvidence reversalEvidence(
            DeepBottomSignal signal,
            BottomStructureState structureState
    ) {
        return new ReversalConfirmationEvidence(
                signal,
                55,
                52,
                structureState,
                null,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
