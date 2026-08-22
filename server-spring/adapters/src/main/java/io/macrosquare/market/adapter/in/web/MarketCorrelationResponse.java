package io.macrosquare.market.adapter.in.web;

import io.macrosquare.market.domain.correlation.MarketCorrelationResult;

import java.time.LocalDate;
import java.util.List;

/** HTTP response contract kept separate from the domain calculation result. */
public record MarketCorrelationResponse(
        int lookbackDays,
        List<String> assets,
        List<List<Double>> matrix,
        List<String> missing,
        LocalDate asOf
) {
    static MarketCorrelationResponse from(MarketCorrelationResult result) {
        return new MarketCorrelationResponse(
                result.lookbackDays(), result.assets(), result.matrix(), result.missing(), result.asOf());
    }
}
