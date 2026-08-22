package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.MarketHistorySeedSeries;
import io.macrosquare.market.domain.observation.MarketObservation;

import java.util.List;

/** Read-only migration input. Implementations must never mutate the legacy source. */
public interface LoadMarketHistorySeedPort {

    List<MarketHistorySeedSeries> listAvailableSeries();

    List<MarketObservation> load(MarketHistorySeedSeries series);
}
