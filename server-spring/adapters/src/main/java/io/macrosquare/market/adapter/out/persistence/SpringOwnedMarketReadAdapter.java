package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;

import java.util.List;
import java.util.Objects;

/** Public read adapter after source ownership cutover. */
public final class SpringOwnedMarketReadAdapter implements LoadMarketReadPort {

    private final LoadMarketSnapshotProjectionPort snapshot;
    private final LoadMarketReadPort histories;

    public SpringOwnedMarketReadAdapter(LoadMarketSnapshotProjectionPort snapshot, LoadMarketReadPort histories) {
        this.snapshot = Objects.requireNonNull(snapshot);
        this.histories = Objects.requireNonNull(histories);
    }

    @Override public Document loadLatestSnapshot() { return snapshot.loadCurrentOrSeed(); }
    @Override public Document loadHistoryCoverage() { return histories.loadHistoryCoverage(); }
    @Override public Document loadHistory(String source, String key) { return histories.loadHistory(source, key); }
    @Override public Document loadHistorySeries(List<String> keys, String range, String interval) {
        return histories.loadHistorySeries(keys, range, interval);
    }
}
