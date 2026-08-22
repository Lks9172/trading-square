package io.macrosquare.company.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/** A transport-neutral numeric value extracted from one guidance clause. */
public record CompanyGuidanceMetricValue(
        String raw,
        BigDecimal min,
        BigDecimal max,
        Unit unit
) {
    public CompanyGuidanceMetricValue {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("raw is required");
        unit = Objects.requireNonNull(unit, "unit");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("min must not exceed max");
        }
    }

    public boolean structured() {
        return unit != Unit.OTHER && (min != null || max != null);
    }

    public enum Unit {
        USD("usd"),
        EUR("eur"),
        PERCENT("percent"),
        BPS("bps"),
        OTHER("other");

        private final String value;

        Unit(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
