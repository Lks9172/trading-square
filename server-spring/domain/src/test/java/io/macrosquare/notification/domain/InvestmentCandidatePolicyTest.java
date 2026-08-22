package io.macrosquare.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestmentCandidatePolicyTest {

    private final InvestmentCandidatePolicy policy = new InvestmentCandidatePolicy();

    @Test
    void requiresCandidateOrBetterBottomAndConfirmedCompanyReversal() {
        assertTrue(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 70, 70, "HOLD", "ON")));
        assertTrue(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 100, 100, "REDUCE", "STRONG")));
        assertTrue(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CANDIDATE, 100, 100, "STRONG BUY", "STRONG")));
        assertFalse(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.UNMET, 100, 100, "STRONG BUY", "STRONG")));
        assertFalse(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 69, 100, "BUY", "ON")));
        assertFalse(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 100, 69, "BUY", "ON")));
        assertFalse(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 100, 100, "BUY", "EARLY")));
        assertFalse(policy.qualifies(candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 100, 100, "BUY", "OFF")));
    }

    @Test
    void detectsOnToStrongAndIndependentFivePointScoreBandCrossings() {
        var previous = candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 74, 71, "REDUCE", "ON");
        var current = candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 80, 75, "HOLD", "STRONG");

        var change = policy.strengthening(previous, current);

        assertTrue(change.strengthened());
        assertTrue(change.reversalBecameStrong());
        assertEquals(70, change.previousTotalScoreBand());
        assertEquals(75, change.currentTotalScoreBand());
        assertEquals(70, change.previousBuyScoreBand());
        assertEquals(80, change.currentBuyScoreBand());
    }

    @Test
    void doesNotNotifyForMovementInsideTheSameFivePointBand() {
        var previous = candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 76, 71, "BUY", "STRONG");
        var current = candidate(
                CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 79, 74, "SELL", "STRONG");

        assertFalse(policy.strengthening(previous, current).strengthened());
    }

    @Test
    void exposesCryptoOnlyWhenTheExecutionActionIsStrongBuy() {
        assertTrue(policy.qualifies(candidate(
                CandidateKind.CRYPTO, BottomCandidateState.CANDIDATE, 70, 70, "STRONG BUY", "OFF")));
        assertFalse(policy.qualifies(candidate(
                CandidateKind.CRYPTO, BottomCandidateState.CONVICTION, 100, 100, "BUY", "STRONG")));
    }

    @Test
    void ranksConvictionAndStrongBottomEvidenceFirstAndHonorsTheLimit() {
        var weak = candidate(CandidateKind.COMPANY, BottomCandidateState.CONVICTION, 75, 75, "BUY", "ON");
        var strong = new InvestmentCandidate(
                CandidateKind.COMPANY, "STRONG", "Strong", "technology",
                BottomCandidateState.CONVICTION, 91, 80, 82, "STRONG BUY",
                LocalDate.of(2026, 7, 20), "STRONG", 88, List.of("volume")
        );

        assertEquals(List.of("STRONG"), policy.qualified(List.of(weak, strong), 1).stream()
                .map(InvestmentCandidate::symbol).toList());
    }

    private static InvestmentCandidate candidate(
            CandidateKind kind,
            BottomCandidateState state,
            int buyScore,
            int totalScore,
            String action,
            String reversalStatus
    ) {
        return new InvestmentCandidate(
                kind, kind.name(), kind.name(), "", state, 75,
                totalScore, buyScore, action, null, reversalStatus, null, List.of()
        );
    }
}
