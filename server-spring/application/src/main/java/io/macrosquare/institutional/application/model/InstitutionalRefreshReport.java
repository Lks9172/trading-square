package io.macrosquare.institutional.application.model;

import java.time.Instant;
import java.util.List;

public record InstitutionalRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int managerCount,
        int filingCount,
        int holdingCount,
        int resolvedIdentityCount,
        List<String> failures
) {
    public InstitutionalRefreshReport {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("institutional refresh timestamps are invalid");
        }
        if (managerCount < 1 || filingCount < 0 || holdingCount < 0 || resolvedIdentityCount < 0) {
            throw new IllegalArgumentException("institutional refresh counts are invalid");
        }
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    public boolean successful() {
        return filingCount > 0 && failures.isEmpty();
    }
}
