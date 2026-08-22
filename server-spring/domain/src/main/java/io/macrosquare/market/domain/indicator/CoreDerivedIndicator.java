package io.macrosquare.market.domain.indicator;

import java.time.LocalDate;

public record CoreDerivedIndicator(
        String key,
        String name,
        Double value,
        LocalDate date,
        String formula
) {
    public CoreDerivedIndicator {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("value must be finite or null");
        if (date == null) throw new IllegalArgumentException("date is required");
        if (formula == null || formula.isBlank()) throw new IllegalArgumentException("formula is required");
    }
}
