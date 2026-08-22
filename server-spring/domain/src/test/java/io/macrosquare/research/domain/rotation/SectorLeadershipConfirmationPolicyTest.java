package io.macrosquare.research.domain.rotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorLeadershipConfirmationPolicyTest {

    private final SectorLeadershipConfirmationPolicy policy =
            new SectorLeadershipConfirmationPolicy();

    @Test
    void confirmsOnlyWhenMomentumMacroEstimatesAndFlowAgree() {
        var result = policy.evaluate(new SectorLeadershipConfirmationEvidence(
                78, 76, 74, 6.0, 10.0, 68, 65, 55,
                SectorRotationState.IMPROVING));

        assertEquals(SectorLeadershipConfirmation.State.CONFIRMED, result.state());
        assertEquals(100, result.evidenceCoveragePct());
        assertTrue(result.reasons().stream().anyMatch(value -> value.contains("상대강도")));
    }

    @Test
    void doesNotCallARankPredictionConfirmedWhenEstimateEvidenceIsMissing() {
        var result = policy.evaluate(new SectorLeadershipConfirmationEvidence(
                78, 76, 74, 6.0, 10.0, null, 65, 55,
                SectorRotationState.IMPROVING));

        assertEquals(SectorLeadershipConfirmation.State.BUILDING, result.state());
        assertEquals(85, result.evidenceCoveragePct());
        assertTrue(result.reasons().stream().anyMatch(value -> value.contains("이익추정")));
    }

    @Test
    void invalidatesWhenBothMomentumHorizonsAndEstimateRevisionsBreak() {
        var result = policy.evaluate(new SectorLeadershipConfirmationEvidence(
                58, 72, 48, -2.0, -5.0, 38, 45, 60,
                SectorRotationState.IMPROVING));

        assertEquals(SectorLeadershipConfirmation.State.INVALIDATED, result.state());
        assertTrue(result.invalidationSignals().size() >= 3);
    }

    @Test
    void priceAndMacroAloneRemainWatchInsteadOfImplyingConfirmationIsBuilding() {
        var result = policy.evaluate(new SectorLeadershipConfirmationEvidence(
                78, 76, 74, 6.0, 10.0, null, null, 55,
                SectorRotationState.IMPROVING));

        assertEquals(SectorLeadershipConfirmation.State.WATCH, result.state());
        assertEquals(70, result.evidenceCoveragePct());
    }
}
