package io.macrosquare.company.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** One positive revenue observation attributed to a semantic filing dimension. */
public record CompanyRevenueMixFact(
        CompanyRevenueMixDimension dimension,
        String dimensionName,
        String label,
        BigDecimal value,
        String unit,
        LocalDate periodStart,
        LocalDate periodEnd
) {
    public CompanyRevenueMixFact {
        dimension = Objects.requireNonNull(dimension, "dimension");
        dimensionName = requireText(dimensionName, "dimensionName");
        label = requireText(label, "label");
        value = Objects.requireNonNull(value, "value");
        unit = requireText(unit, "unit");
        periodStart = Objects.requireNonNull(periodStart, "periodStart");
        periodEnd = Objects.requireNonNull(periodEnd, "periodEnd");
        if (value.signum() <= 0) throw new IllegalArgumentException("value must be positive");
        if (periodStart.isAfter(periodEnd)) throw new IllegalArgumentException("periodStart must not exceed periodEnd");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
