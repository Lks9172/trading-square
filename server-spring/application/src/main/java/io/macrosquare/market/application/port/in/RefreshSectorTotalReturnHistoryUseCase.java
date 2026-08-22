package io.macrosquare.market.application.port.in;

import io.macrosquare.market.application.model.SectorTotalReturnRefreshReport;

public interface RefreshSectorTotalReturnHistoryUseCase {
    SectorTotalReturnRefreshReport refresh();
}
