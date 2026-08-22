package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyExpectationAssessment.State;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyExpectationAssessmentPolicyTest {

    private final CompanyExpectationAssessmentPolicy policy = new CompanyExpectationAssessmentPolicy();

    @Test
    void keepsTargetUpsideMovementOutOfTheEpsRevisionScore() {
        var result = policy.evaluate(new CompanyMarketExpectations(
                30.0, null, null, null, 25.0, null), 50);

        assertEquals(State.UNAVAILABLE, result.state());
        assertEquals(50, result.score());
        assertTrue(result.cautions().stream().anyMatch(value -> value.contains("대체하지 않습니다")));
    }

    @Test
    void evaluatesActualForwardEpsRevisionsAcrossWindows() {
        var result = policy.evaluate(new CompanyMarketExpectations(
                20.0, 1.0, 4.0, 8.0, -5.0, null), 48);

        assertEquals(State.IMPROVING, result.state());
        assertTrue(result.reasons().stream().anyMatch(value -> value.contains("30일 EPS 추정치 +4.0%")));
    }
}
