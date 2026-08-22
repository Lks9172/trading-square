package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.rotation.SectorConstituentPriceSeries;

/** Cross-context read port; adapters must translate Company price types. */
@FunctionalInterface
public interface LoadSectorConstituentPriceHistoryPort {
    SectorConstituentPriceSeries load(String normalizedTicker);
}
