package io.macrosquare.market.application.model;

import io.macrosquare.market.domain.observation.MarketDataSource;

/** Provider-neutral identity of one bounded history series available for migration seeding. */
public record MarketHistorySeedSeries(
        MarketDataSource source,
        String key,
        String providerCode
) {
    public MarketHistorySeedSeries {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (providerCode == null || providerCode.isBlank()) {
            throw new IllegalArgumentException("providerCode is required");
        }
    }
}
