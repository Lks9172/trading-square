package io.macrosquare.policy.application.model;

import java.time.Instant;
import java.util.List;

public record PolicyRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int collected,
        int persisted,
        List<String> failures
) {
    public PolicyRefreshReport {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("policy refresh timestamps are invalid");
        }
        failures = List.copyOf(failures == null ? List.of() : failures);
        if (collected < 0 || persisted < 0 || persisted > collected) {
            throw new IllegalArgumentException("policy refresh counts are invalid");
        }
        if (persisted + failures.size() != collected) {
            throw new IllegalArgumentException("every collected policy document must be persisted or failed");
        }
    }

    public boolean successful() {
        return collected > 0 && persisted == collected && failures.isEmpty();
    }
}
