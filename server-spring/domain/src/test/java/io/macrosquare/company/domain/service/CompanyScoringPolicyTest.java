package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFinancials;
import io.macrosquare.company.domain.model.Ticker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyScoringPolicyTest {

    private final CompanyScoringPolicy policy = new CompanyScoringPolicy();

    @Test
    void matchesTheExistingTypeScriptScoringRules() {
        var financials = new CompanyFinancials(
                new Ticker("test"),
                25.0,
                25.0,
                20.0,
                20.0,
                3.0,
                3.0,
                20.0,
                0.0,
                150.0,
                90.0,
                0.0,
                3.0
        );

        var score = policy.evaluate(financials);

        assertEquals("TEST", score.ticker().value());
        assertEquals(87, score.totalScore());
        assertEquals(90, score.growth().value());
        assertEquals(87, score.quality().value());
        assertEquals(85, score.valuation().value());
        assertEquals(84, score.balanceSheet().value());
        assertEquals(4, score.reasons().size());
        assertTrue(score.reasons().getFirst().contains("25.0% 고성장"));
    }

    @Test
    void returnsZeroWithoutFabricatingMissingFacts() {
        var financials = new CompanyFinancials(
                new Ticker("NONE"),
                null, null, null, null, null, null, null,
                null, null, null, null, null
        );

        var score = policy.evaluate(financials);

        assertEquals(0, score.totalScore());
        assertTrue(score.reasons().isEmpty());
    }
}
