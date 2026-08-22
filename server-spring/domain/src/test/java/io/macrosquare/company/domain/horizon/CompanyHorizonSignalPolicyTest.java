package io.macrosquare.company.domain.horizon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyHorizonSignalPolicyTest {

    private final CompanyHorizonSignalPolicy policy = new CompanyHorizonSignalPolicy();

    @Test
    void shortTermPrioritizesTimingWhileLongTermPrioritizesBusinessQuality() {
        var view = policy.evaluate(new CompanyHorizonEvidence(
                50, 20, 20, 20, 20, 80, 90, 90, 90));

        assertTrue(view.shortTerm().score() > view.longTerm().score());
        assertEquals(CompanyHorizonAction.BUY, view.shortTerm().action());
        assertEquals(CompanyHorizonAction.SELL, view.longTerm().action());
        assertEquals(100, view.shortTerm().confidence());
        assertEquals(100, CompanyHorizonSignalPolicy.SHORT_WEIGHTS.total());
        assertEquals(100, CompanyHorizonSignalPolicy.SWING_WEIGHTS.total());
        assertEquals(100, CompanyHorizonSignalPolicy.LONG_WEIGHTS.total());
    }

    @Test
    void missingEvidenceProducesHoldInsteadOfAFalseReductionCall() {
        var view = policy.evaluate(new CompanyHorizonEvidence(
                null, null, null, null, null, null, null, null, null));

        assertEquals(0, view.shortTerm().confidence());
        assertEquals(CompanyHorizonAction.HOLD, view.shortTerm().action());
        assertEquals(CompanyHorizonAction.HOLD, view.swingTerm().action());
        assertEquals(CompanyHorizonAction.HOLD, view.longTerm().action());
    }
}
