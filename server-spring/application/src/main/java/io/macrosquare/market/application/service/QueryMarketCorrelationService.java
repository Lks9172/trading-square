package io.macrosquare.market.application.service;

import io.macrosquare.market.application.port.in.QueryMarketCorrelationUseCase;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.correlation.MarketCorrelationPolicy;
import io.macrosquare.market.domain.indicator.MarketSeriesPoint;
import io.macrosquare.market.domain.observation.MarketDataSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class QueryMarketCorrelationService implements QueryMarketCorrelationUseCase {

    private static final List<String> DEFAULT_ASSETS = List.of(
            "NASDAQ", "SP500", "GOLD", "SILVER", "COPPER", "WTI", "DXY", "USDKRW", "KOSPI", "DGS10", "VIXCLS");
    private static final Set<String> FRED = Set.of("DGS10", "VIXCLS");
    private final MarketObservationRepository repository;
    private final MarketCorrelationPolicy policy;
    private final Clock clock;

    public QueryMarketCorrelationService(
            MarketObservationRepository repository,
            MarketCorrelationPolicy policy,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public io.macrosquare.market.domain.correlation.MarketCorrelationResult query(
            int lookbackDays,
            List<String> requestedAssets
    ) {
        var requested = new LinkedHashSet<String>();
        (requestedAssets == null || requestedAssets.isEmpty() ? DEFAULT_ASSETS : requestedAssets).forEach(value -> {
            if (value == null) return;
            var key = value.trim().toUpperCase(Locale.ROOT);
            if (DEFAULT_ASSETS.contains(key)) requested.add(key);
        });
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        requested.forEach(key -> histories.put(key, repository.loadHistory(
                        FRED.contains(key) ? MarketDataSource.FRED : MarketDataSource.YAHOO, key).stream()
                .map(value -> new MarketSeriesPoint(value.observationDate(), value.value())).toList()));
        return policy.evaluate(
                Math.max(10, Math.min(500, lookbackDays)), List.copyOf(requested), Map.copyOf(histories),
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
