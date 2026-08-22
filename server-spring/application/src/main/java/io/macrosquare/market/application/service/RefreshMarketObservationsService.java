package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketCollectionReport;
import io.macrosquare.market.application.port.in.RefreshMarketObservationsUseCase;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

public final class RefreshMarketObservationsService implements RefreshMarketObservationsUseCase {

    private final EnumMap<MarketDataSource, CollectMarketObservationsPort> collectors;
    private final MarketObservationRepository repository;

    public RefreshMarketObservationsService(
            List<CollectMarketObservationsPort> collectors,
            MarketObservationRepository repository
    ) {
        this.collectors = new EnumMap<>(MarketDataSource.class);
        for (var collector : collectors) {
            var previous = this.collectors.put(collector.source(), collector);
            if (previous != null) throw new IllegalArgumentException("duplicate collector for " + collector.source());
        }
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public MarketCollectionReport refresh(MarketDataSource source) {
        var collector = collectors.get(Objects.requireNonNull(source));
        if (collector == null) throw new IllegalArgumentException("collector is unavailable for " + source);
        var batch = collector.collect();
        var persisted = batch.observations().isEmpty() ? 0 : repository.save(batch.observations());
        return new MarketCollectionReport(
                source,
                batch.startedAt(),
                batch.completedAt(),
                batch.observations().size(),
                persisted,
                batch.failures()
        );
    }
}
