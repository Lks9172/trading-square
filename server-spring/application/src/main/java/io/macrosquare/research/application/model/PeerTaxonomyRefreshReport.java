package io.macrosquare.research.application.model;

import java.time.Instant;
import java.util.List;

public record PeerTaxonomyRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int universeCount,
        int attemptedCount,
        int persistedCount,
        List<String> failures
) {
    public PeerTaxonomyRefreshReport {
        failures = List.copyOf(failures);
    }
}
