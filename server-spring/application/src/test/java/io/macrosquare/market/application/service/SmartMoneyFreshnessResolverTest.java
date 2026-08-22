package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartMoneyFreshnessResolverTest {

    private final MarketInputFreshnessPolicy freshness = new MarketInputFreshnessPolicy();
    private final LocalDate asOf = LocalDate.parse("2026-08-06");

    @Test
    void usesCurrentSmartMoneyEvidence() {
        var result = SmartMoneyFreshnessResolver.resolve(input("2026-07-31", -2), asOf, freshness);

        assertTrue(result.eligibleForRegime());
        assertEquals(-2d, result.scoreForDecision());
        assertEquals(6, result.ageDays());
    }

    @Test
    void preservesButNeutralizesStaleSmartMoneyEvidence() {
        var result = SmartMoneyFreshnessResolver.resolve(input("2026-07-20", -2), asOf, freshness);

        assertFalse(result.eligibleForRegime());
        assertEquals(-2d, result.observedScore());
        assertEquals(0d, result.scoreForDecision());
        assertEquals(17, result.ageDays());
    }

    @Test
    void neutralizesMissingOrFutureEvidence() {
        assertFalse(SmartMoneyFreshnessResolver.resolve(Map.of(), asOf, freshness).eligibleForRegime());
        assertFalse(SmartMoneyFreshnessResolver.resolve(
                input("2026-08-07", 2), asOf, freshness).eligibleForRegime());
    }

    private static Map<String, StructuredValue> input(String date, long score) {
        return Map.of("lastUpdated", new TextValue(date), "score", new NumberValue(score));
    }
}
