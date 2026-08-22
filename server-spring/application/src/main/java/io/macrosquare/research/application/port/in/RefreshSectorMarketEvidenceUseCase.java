package io.macrosquare.research.application.port.in;

import io.macrosquare.research.application.model.SectorMarketEvidenceRefreshReport;

@FunctionalInterface
public interface RefreshSectorMarketEvidenceUseCase {
    SectorMarketEvidenceRefreshReport refresh();
}
