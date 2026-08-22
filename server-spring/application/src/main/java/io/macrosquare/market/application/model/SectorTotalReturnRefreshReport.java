package io.macrosquare.market.application.model;

import java.time.Instant;
import java.util.List;

public record SectorTotalReturnRefreshReport(
        Instant startedAt,
        Instant completedAt,
        boolean fullBackfill,
        int expectedSeries,
        int collected,
        int persisted,
        List<MarketCollectionBatch.Failure> failures
) {
    public SectorTotalReturnRefreshReport {
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("refresh timestamps are invalid");
        }
        if (expectedSeries < 1 || collected < 0 || persisted < 0) {
            throw new IllegalArgumentException("refresh counts are invalid");
        }
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    public boolean successful() {
        return failures.isEmpty() && collected > 0 && collected == persisted;
    }
}
