package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.model.MarketCollectionStatus;
import io.macrosquare.market.application.port.out.MarketCollectionStatusRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Process-local compatibility store used outside the PostgreSQL production profile. */
public final class InMemoryMarketCollectionStatusRepository implements MarketCollectionStatusRepository {

    private final EnumMap<MarketDataSource, MarketCollectionStatus> values =
            new EnumMap<>(MarketDataSource.class);

    @Override
    public synchronized void save(MarketCollectionStatus status) {
        Objects.requireNonNull(status);
        var previous = values.get(status.source());
        if (previous == null || !status.completedAt().isBefore(previous.completedAt())) {
            values.put(status.source(), status);
        }
    }

    @Override
    public synchronized Map<MarketDataSource, MarketCollectionStatus> loadLatest() {
        return Map.copyOf(values);
    }
}
