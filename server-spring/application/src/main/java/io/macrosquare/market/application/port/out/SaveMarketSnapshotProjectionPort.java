package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketReadModels.Document;

public interface SaveMarketSnapshotProjectionPort {
    void save(Document snapshot);
}
