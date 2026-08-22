package io.macrosquare.market.application.port.in;

import io.macrosquare.market.application.model.MarketCollectionReport;
import io.macrosquare.market.domain.observation.MarketDataSource;

public interface RefreshMarketObservationsUseCase {
    MarketCollectionReport refresh(MarketDataSource source);
}
