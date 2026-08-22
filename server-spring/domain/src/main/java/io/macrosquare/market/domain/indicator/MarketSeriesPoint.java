package io.macrosquare.market.domain.indicator;

import java.time.LocalDate;

public record MarketSeriesPoint(LocalDate date, double value) {
    public MarketSeriesPoint {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (!Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
    }
}
