package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.rotation.SectorEarningsRevisionBreadth;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Loads point-in-time constituent revision breadth without exposing persistence types. */
@FunctionalInterface
public interface LoadSectorEarningsRevisionBreadthPort {

    Optional<SectorEarningsRevisionBreadth> load(
            String sectorKey,
            List<String> normalizedTickers,
            LocalDate asOfDate,
            int maxAgeDays
    );

    static LoadSectorEarningsRevisionBreadthPort unavailable() {
        return (sectorKey, tickers, asOfDate, maxAgeDays) -> Optional.empty();
    }
}
