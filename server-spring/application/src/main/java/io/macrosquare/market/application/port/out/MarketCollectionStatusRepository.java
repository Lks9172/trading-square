package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketCollectionStatus;
import io.macrosquare.market.domain.observation.MarketDataSource;

import java.util.Map;

/** Stores operational collection evidence separately from financial observations. */
public interface MarketCollectionStatusRepository {

    void save(MarketCollectionStatus status);

    Map<MarketDataSource, MarketCollectionStatus> loadLatest();

    static MarketCollectionStatusRepository none() {
        return new MarketCollectionStatusRepository() {
            @Override
            public void save(MarketCollectionStatus status) {
            }

            @Override
            public Map<MarketDataSource, MarketCollectionStatus> loadLatest() {
                return Map.of();
            }
        };
    }
}
