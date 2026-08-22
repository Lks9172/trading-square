package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment.SourceUnit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioAllocationPolicyTest {

    private final PortfolioAllocationPolicy policy = new PortfolioAllocationPolicy();

    @Test
    void convertsLegacyKrwAmountsWithoutPresentingThemAsPercentages() {
        var holdings = new LinkedHashMap<String, Double>();
        holdings.put("cash", 6_000_000d);
        holdings.put("gold", 9_846_750d);
        holdings.put("nasdaq", 2_520_000d);
        var assessment = policy.assess(plan(holdings, 15_000_000L));

        assertEquals(SourceUnit.KRW_ABSOLUTE, assessment.sourceUnit());
        assertTrue(assessment.normalized());
        assertEquals(122.44, assessment.allocatedPct(), .02);
        assertEquals(40, assessment.percentages().get("cash"), .01);
        assertEquals(65.65, assessment.percentages().get("gold"), .01);
        assertEquals(22.44, assessment.overAllocatedPct(), .02);
        assertTrue(assessment.cautions().getFirst().contains("초과"));
    }

    @Test
    void preservesValidPercentagesAndTracksUnallocatedCapital() {
        var assessment = policy.assess(plan(Map.of("cash", 20d, "nasdaq", 50d), 100_000_000L));

        assertEquals(SourceUnit.PERCENT, assessment.sourceUnit());
        assertEquals(Map.of("cash", 20d, "nasdaq", 50d), assessment.percentages());
        assertEquals(30, assessment.unallocatedPct());
        assertEquals(0, assessment.overAllocatedPct());
        assertTrue(!assessment.normalized());
    }

    @Test
    void preservesOverAllocatedPercentageInputsInsteadOfHidingLeverage() {
        var assessment = policy.assess(plan(Map.of("cash", 30d, "nasdaq", 85d), 100_000_000L));

        assertEquals(115, assessment.allocatedPct());
        assertEquals(15, assessment.overAllocatedPct());
        assertEquals(85, assessment.percentages().get("nasdaq"));
        assertTrue(assessment.cautions().getFirst().contains("그대로 표시"));
    }

    @Test
    void aSingleLeveragedWeightAboveOneHundredIsStillAPercentage() {
        var assessment = policy.assess(plan(Map.of("nasdaq", 115d), 100_000_000L));

        assertEquals(SourceUnit.PERCENT, assessment.sourceUnit());
        assertEquals(115, assessment.percentages().get("nasdaq"));
        assertEquals(15, assessment.overAllocatedPct());
    }

    @Test
    void calculatesDriftFromNormalizedWeightsRatherThanRawKrwAmounts() {
        var assessment = policy.assess(plan(Map.of("cash", 6_000_000d, "gold", 4_000_000d), 10_000_000L));
        var drift = policy.drift(assessment, Map.of("cash", 20, "gold", 80));

        assertEquals(40, drift.totalDriftPct());
        assertEquals(40, drift.weights().getFirst().differencePct());
        assertTrue(drift.exceeding(10).stream().allMatch(value -> value.differencePct() <= 100));
    }

    private static InvestmentPlan plan(Map<String, Double> holdings, Long totalCapital) {
        return new InvestmentPlan(
                InvestmentHorizon.MEDIUM, 12, 25, 90, 15, 25, 15, 1_000_000,
                holdings, totalCapital, null, null, null, null, null, null, null, null,
                Instant.parse("2026-08-05T00:00:00Z")
        );
    }
}
