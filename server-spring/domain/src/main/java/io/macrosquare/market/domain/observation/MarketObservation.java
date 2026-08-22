package io.macrosquare.market.domain.observation;

import java.time.LocalDate;

public record MarketObservation(
        String key,
        String providerCode,
        double value,
        LocalDate observationDate,
        MarketDataSource source
) {
    public MarketObservation {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (providerCode == null || providerCode.isBlank()) {
            throw new IllegalArgumentException("providerCode is required");
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        if (observationDate == null) throw new IllegalArgumentException("observationDate is required");
        if (source == null) throw new IllegalArgumentException("source is required");
    }
}
