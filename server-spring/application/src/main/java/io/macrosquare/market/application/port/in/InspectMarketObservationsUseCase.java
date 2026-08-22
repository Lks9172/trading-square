package io.macrosquare.market.application.port.in;

import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;

import java.util.List;
import java.util.Map;

public interface InspectMarketObservationsUseCase {
    Map<MarketDataSource, List<MarketObservation>> latest();
}
