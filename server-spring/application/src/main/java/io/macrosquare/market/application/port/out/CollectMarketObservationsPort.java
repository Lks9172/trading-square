package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.domain.observation.MarketDataSource;

public interface CollectMarketObservationsPort {
    MarketDataSource source();

    MarketCollectionBatch collect();
}
