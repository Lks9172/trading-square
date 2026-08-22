package io.macrosquare.research.application.model;

import java.time.Instant;
import java.util.List;

public record SectorMarketEvidenceRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int attemptedSectors,
        int fundFlowWritten,
        int priceBreadthWritten,
        List<Failure> failures
) {
    public SectorMarketEvidenceRefreshReport {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("refresh timestamps are invalid");
        }
        if (attemptedSectors < 0 || fundFlowWritten < 0 || priceBreadthWritten < 0) {
            throw new IllegalArgumentException("refresh counts must not be negative");
        }
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    public boolean successful() { return failures.isEmpty(); }

    public record Failure(String sectorKey, String evidenceType, String reason) {
        public Failure {
            if (sectorKey == null || sectorKey.isBlank()) throw new IllegalArgumentException("sectorKey is required");
            if (evidenceType == null || evidenceType.isBlank()) throw new IllegalArgumentException("evidenceType is required");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        }
    }
}
