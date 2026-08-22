package io.macrosquare.market.application.port.in;

import io.macrosquare.market.domain.correlation.MarketCorrelationResult;

import java.util.List;

public interface QueryMarketCorrelationUseCase {
    MarketCorrelationResult query(int lookbackDays, List<String> requestedAssets);
}
