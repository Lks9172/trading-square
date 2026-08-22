package io.macrosquare.market.application.port.in;

import io.macrosquare.market.application.model.MarketReadModels.Document;

import java.time.Instant;

public record MarketSnapshotRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int rawCount,
        int derivedCount,
        int coreDerivedCount,
        String regime,
        int regimeScore,
        Document snapshot
) {
    public MarketSnapshotRefreshReport {
        if (startedAt == null || completedAt == null || snapshot == null) {
            throw new IllegalArgumentException("timestamps and snapshot are required");
        }
    }
}
