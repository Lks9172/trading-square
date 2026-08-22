package io.macrosquare.market.application.service;

import io.macrosquare.market.application.port.in.InspectMarketObservationsUseCase;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InspectMarketObservationsService implements InspectMarketObservationsUseCase {

    private final MarketObservationRepository repository;

    public InspectMarketObservationsService(MarketObservationRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Map<MarketDataSource, List<MarketObservation>> latest() {
        var result = new EnumMap<MarketDataSource, List<MarketObservation>>(MarketDataSource.class);
        for (var source : MarketDataSource.values()) result.put(source, repository.loadLatest(source));
        return Map.copyOf(result);
    }
}
