package io.macrosquare.company.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Consolidated revenue used to validate whether dimensional facts form a complete mix. */
public record CompanyRevenueTotal(
        BigDecimal value,
        String unit,
        LocalDate periodStart,
        LocalDate periodEnd
) {
    public CompanyRevenueTotal {
        value = Objects.requireNonNull(value, "value");
        if (value.signum() <= 0) throw new IllegalArgumentException("value must be positive");
        if (unit == null || unit.isBlank()) throw new IllegalArgumentException("unit is required");
        unit = unit.trim();
        periodStart = Objects.requireNonNull(periodStart, "periodStart");
        periodEnd = Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodStart.isAfter(periodEnd)) throw new IllegalArgumentException("periodStart must not exceed periodEnd");
    }
}
