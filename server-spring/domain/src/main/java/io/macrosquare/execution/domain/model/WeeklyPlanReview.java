package io.macrosquare.execution.domain.model;

import java.util.List;

public record WeeklyPlanReview(
        PortfolioAllocationAssessment holdings,
        PortfolioDriftAssessment drift,
        List<String> ruleViolations
) {
    public WeeklyPlanReview {
        if (holdings == null || drift == null) {
            throw new IllegalArgumentException("holdings and drift are required");
        }
        ruleViolations = List.copyOf(ruleViolations == null ? List.of() : ruleViolations);
    }
}
