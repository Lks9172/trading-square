package io.macrosquare.crypto.adapter.out.market;

import io.macrosquare.crypto.application.port.out.LoadCryptoMarketSeriesPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.crypto.application.model.CryptoPricePoint;
import io.macrosquare.market.domain.observation.MarketDataSource;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MarketObservationCryptoSeriesAdapter implements LoadCryptoMarketSeriesPort {

    private static final Set<String> SYMBOLS = Set.of("BTC", "ETH", "SOL", "XRP", "BNB");
    private final MarketObservationRepository repository;

    public MarketObservationCryptoSeriesAdapter(MarketObservationRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public List<CryptoPricePoint> load(String symbol) {
        var normalized = symbol.toUpperCase(Locale.ROOT);
        if (!SYMBOLS.contains(normalized)) return List.of();
        return repository.loadHistory(MarketDataSource.YAHOO, normalized).stream()
                .map(value -> new CryptoPricePoint(value.observationDate(), value.value())).toList();
    }
}
