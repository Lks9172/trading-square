package io.macrosquare.execution.application.model;

import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment;
import io.macrosquare.execution.domain.model.PortfolioDriftAssessment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WeeklyReviewReport(
        Instant generatedAt,
        LocalDate periodFrom,
        LocalDate periodTo,
        String regime,
        int regimeScore,
        List<SignalReview> keySignals,
        List<String> topReasons,
        List<String> warnings,
        List<EventReview> nextEvents,
        List<String> ruleViolations,
        PortfolioAllocationAssessment holdings,
        PortfolioDriftAssessment drift,
        String text
) {
    public WeeklyReviewReport {
        if (generatedAt == null || periodFrom == null || periodTo == null) {
            throw new IllegalArgumentException("weekly review timestamps are required");
        }
        keySignals = List.copyOf(keySignals == null ? List.of() : keySignals);
        topReasons = List.copyOf(topReasons == null ? List.of() : topReasons);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        nextEvents = List.copyOf(nextEvents == null ? List.of() : nextEvents);
        ruleViolations = List.copyOf(ruleViolations == null ? List.of() : ruleViolations);
        if (holdings == null || drift == null || text == null) {
            throw new IllegalArgumentException("weekly review details are required");
        }
    }

    public record SignalReview(String asset, String signal, String met, int dataCoveragePct) {
    }

    public record EventReview(String event, LocalDate date, long dday, String importance) {
    }
}
