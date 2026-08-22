package io.macrosquare.company.domain.model;

import java.util.Objects;

/** Direction, evidence clause, and optional numeric value for one guidance metric. */
public record CompanyGuidanceMetric(
        Direction direction,
        String text,
        CompanyGuidanceMetricValue value
) {
    public CompanyGuidanceMetric {
        direction = Objects.requireNonNull(direction, "direction");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
    }

    public boolean structured() {
        return value != null && value.structured();
    }

    public enum Direction {
        RAISED("raised"),
        LOWERED("lowered"),
        AFFIRMED("affirmed"),
        MENTIONED("mentioned");

        private final String value;

        Direction(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
