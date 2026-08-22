package io.macrosquare.market.application.port.in;

import java.time.Instant;
import java.util.List;

public record MarketHistorySeedReport(
        Instant startedAt,
        Instant completedAt,
        int availableSeries,
        int seededSeries,
        int skippedExistingSeries,
        int persistedPoints,
        List<String> failures
) {
    public MarketHistorySeedReport {
        if (startedAt == null || completedAt == null) throw new IllegalArgumentException("timestamps are required");
        if (completedAt.isBefore(startedAt)) throw new IllegalArgumentException("timestamps are out of order");
        failures = List.copyOf(failures == null ? List.of() : failures);
        if (availableSeries < 0 || seededSeries < 0 || skippedExistingSeries < 0 || persistedPoints < 0) {
            throw new IllegalArgumentException("history seed counts must not be negative");
        }
        if (seededSeries + skippedExistingSeries + failures.size() != availableSeries) {
            throw new IllegalArgumentException("every seed series must be seeded, skipped or failed");
        }
    }

    public boolean successful() {
        return availableSeries > 0
                && seededSeries + skippedExistingSeries == availableSeries
                && failures.isEmpty();
    }
}
