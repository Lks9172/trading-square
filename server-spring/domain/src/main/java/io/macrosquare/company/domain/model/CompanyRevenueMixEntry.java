package io.macrosquare.company.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

public record CompanyRevenueMixEntry(
        String label,
        BigDecimal value,
        BigDecimal percentOfTotal
) {
    public CompanyRevenueMixEntry {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        label = label.trim();
        value = Objects.requireNonNull(value, "value");
        percentOfTotal = Objects.requireNonNull(percentOfTotal, "percentOfTotal");
        if (value.signum() <= 0) throw new IllegalArgumentException("value must be positive");
        if (percentOfTotal.signum() < 0 || percentOfTotal.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("percentOfTotal must be between zero and 100");
        }
    }
}
