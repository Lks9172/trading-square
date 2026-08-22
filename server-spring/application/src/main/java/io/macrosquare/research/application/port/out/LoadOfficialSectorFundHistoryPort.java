package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.rotation.SectorFundHistoryPoint;

import java.util.List;

/** Loads official issuer NAV and shares-outstanding history for one sector ETF. */
@FunctionalInterface
public interface LoadOfficialSectorFundHistoryPort {
    List<SectorFundHistoryPoint> load(String fundTicker);
}
