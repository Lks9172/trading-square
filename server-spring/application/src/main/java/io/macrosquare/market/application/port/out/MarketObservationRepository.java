package io.macrosquare.market.application.port.out;

import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;

import java.util.List;

public interface MarketObservationRepository {
    int save(List<MarketObservation> observations);

    List<MarketObservation> loadLatest(MarketDataSource source);

    List<MarketObservation> loadHistory(MarketDataSource source, String key);
}
