package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.bottom.BottomPatternPoint;

import java.util.List;

/** Loads immutable daily close/volume evidence without exposing the market-data transport. */
@FunctionalInterface
public interface LoadCompanyPriceHistoryPort {

    List<BottomPatternPoint> load(String normalizedTicker);
}
