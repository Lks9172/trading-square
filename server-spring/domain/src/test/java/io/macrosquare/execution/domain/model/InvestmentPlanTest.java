package io.macrosquare.execution.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvestmentPlanTest {

    @Test
    void preservesLegacyAbsoluteHoldingsDuringLosslessMigration() {
        var plan = plan(Map.of("nasdaq", 2_520_000d), Map.of("gold", 12_345d));

        assertEquals(2_520_000d, plan.currentHoldings().get("nasdaq"));
        assertEquals(12_345d, plan.currentHoldingsUsd().get("gold"));
    }

    @Test
    void stillRejectsNegativeOrUnsupportedHoldings() {
        assertThrows(IllegalArgumentException.class, () -> plan(Map.of("nasdaq", -1d), null));
        assertThrows(IllegalArgumentException.class, () -> plan(Map.of("unknown", 1d), null));
    }

    private static InvestmentPlan plan(Map<String, Double> holdings, Map<String, Double> holdingsUsd) {
        return new InvestmentPlan(
                InvestmentHorizon.MEDIUM,
                12,
                25,
                90,
                15,
                25,
                15,
                1_000_000,
                holdings,
                21_000_000L,
                20_000d,
                holdingsUsd,
                null,
                null,
                null,
                4d,
                null,
                null,
                Instant.parse("2026-07-20T00:00:00Z")
        );
    }
}
