package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.rotation.SectorTotalReturnPoint;

import java.util.List;

public interface LoadSectorTotalReturnHistoryPort {
    List<SectorTotalReturnPoint> load(String seriesKey);
}
