package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketReadModels.Document;

/** Loads the Spring-owned snapshot, or the immutable migration seed before the first write. */
public interface LoadMarketSnapshotProjectionPort {
    Document loadCurrentOrSeed();
}
