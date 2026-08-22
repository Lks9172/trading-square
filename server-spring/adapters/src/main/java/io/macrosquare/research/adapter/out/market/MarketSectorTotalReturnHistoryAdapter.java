package io.macrosquare.research.adapter.out.market;

import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.research.application.port.out.LoadSectorTotalReturnHistoryPort;
import io.macrosquare.research.domain.rotation.SectorTotalReturnPoint;

import java.util.List;
import java.util.Objects;

/** Research read adapter over the market bounded context's immutable observation port. */
public final class MarketSectorTotalReturnHistoryAdapter implements LoadSectorTotalReturnHistoryPort {

    private final MarketObservationRepository observations;

    public MarketSectorTotalReturnHistoryAdapter(MarketObservationRepository observations) {
        this.observations = Objects.requireNonNull(observations);
    }

    @Override
    public List<SectorTotalReturnPoint> load(String seriesKey) {
        return observations.loadHistory(MarketDataSource.YAHOO, seriesKey).stream()
                .map(value -> new SectorTotalReturnPoint(value.observationDate(), value.value()))
                .toList();
    }
}
