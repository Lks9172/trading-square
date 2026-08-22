package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketCollectionBatch;

/** Loads split- and distribution-adjusted sector ETF histories from a market-data provider. */
public interface CollectSectorTotalReturnHistoryPort {

    MarketCollectionBatch collect(HistoryWindow window);

    enum HistoryWindow {
        FULL,
        RECENT
    }
}
