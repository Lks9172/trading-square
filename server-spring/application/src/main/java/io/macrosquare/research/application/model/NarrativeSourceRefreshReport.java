package io.macrosquare.research.application.model;

import java.time.Instant;

public record NarrativeSourceRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int attemptedCount,
        int persistedCount,
        int availableCount,
        int missingCount,
        int failedCount
) {
    public NarrativeSourceRefreshReport {
        if (startedAt == null || completedAt == null) throw new IllegalArgumentException("timestamps are required");
        if (attemptedCount < 0 || persistedCount < 0 || availableCount < 0 || missingCount < 0 || failedCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
