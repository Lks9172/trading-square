package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.TrancheEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanPolicyTest {

    private final ExecutionPlanPolicy policy = new ExecutionPlanPolicy();

    @Test
    void preservesLegacyThirtyThirtyFortyWeightsAndThreeStageSummary() {
        assertEquals(30d, policy.fallbackWeight(1));
        assertEquals(30d, policy.fallbackWeight(2));
        assertEquals(40d, policy.fallbackWeight(3));
        assertNull(policy.fallbackWeight(4));

        var summary = policy.summarize(List.of(
                tranche(2, "2026-07-20T02:00:00Z"),
                tranche(1, "2026-07-20T01:00:00Z"),
                tranche(2, "2026-07-20T03:00:00Z")
        )).getFirst();

        assertEquals(List.of(1, 2), summary.executedStages());
        assertEquals(3, summary.nextStage());
        assertEquals(Instant.parse("2026-07-20T03:00:00Z"), summary.latestExecutedAt());
    }

    @Test
    void classifiesUserActionsAgainstTheCurrentRecommendation() {
        assertTrue(policy.againstRecommendation("SELL", "STRONG_BUY"));
        assertFalse(policy.againstRecommendation("ADD", "BUY"));
        assertTrue(policy.againstRecommendation("ENTER", "REDUCE"));
        assertNull(policy.againstRecommendation("WAIT", "BUY"));
    }

    private static TrancheEntry tranche(int stage, String time) {
        return new TrancheEntry("NASDAQ", stage, Instant.parse(time), 100d, "RISK_ON", null);
    }
}
