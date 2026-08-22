package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyPlanReviewPolicyTest {

    private final WeeklyPlanReviewPolicy policy = new WeeklyPlanReviewPolicy(new PortfolioAllocationPolicy());

    @Test
    void weeklyDriftUsesNormalizedLegacyAmountsAndNeverPrintsMillionPercent() {
        var plan = new InvestmentPlan(
                InvestmentHorizon.MEDIUM, 12, 25, 90, 15, 25, 15, 1_000_000,
                Map.of("cash", 6_000_000d, "gold", 4_000_000d), 10_000_000L,
                null, null, null, null, null, null, null, null,
                Instant.parse("2026-08-05T00:00:00Z")
        );

        var review = policy.evaluate(
                plan, Map.of("cash", 20, "gold", 80), java.util.List.of(),
                Instant.parse("2026-08-05T12:00:00Z"));

        assertTrue(review.ruleViolations().stream().anyMatch(value -> value.contains("gold 40%p")));
        assertFalse(review.ruleViolations().stream().anyMatch(value -> value.contains("4000000")));
        assertFalse(review.ruleViolations().stream().anyMatch(value -> value.contains("6000000")));
    }

    @Test
    void weeklyReviewDoesNotHideGrossExposureAboveConfiguredCapital() {
        var plan = new InvestmentPlan(
                InvestmentHorizon.MEDIUM, 12, 25, 90, 15, 25, 15, 1_000_000,
                Map.of("cash", 6_000_000d, "gold", 9_000_000d), 10_000_000L,
                null, null, null, null, null, null, null, null,
                Instant.parse("2026-08-05T00:00:00Z")
        );

        var review = policy.evaluate(
                plan, Map.of("cash", 30, "gold", 70), java.util.List.of(),
                Instant.parse("2026-08-05T12:00:00Z"));

        assertTrue(review.ruleViolations().stream().anyMatch(value ->
                value.contains("실제 보유 노출 150%") && value.contains("50%p 초과")));
    }
}
