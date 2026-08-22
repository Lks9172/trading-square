package io.macrosquare.integrity.application.model;

import io.macrosquare.integrity.domain.DataIntegrityReport;

import java.util.Objects;

public record DataIntegrityCheckResult(
        DataIntegrityReport report,
        IntegrityIncidentTransition transition
) {
    public DataIntegrityCheckResult {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(transition, "transition");
    }
}
