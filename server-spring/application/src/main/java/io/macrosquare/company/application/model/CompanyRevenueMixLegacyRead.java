package io.macrosquare.company.application.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Transport-neutral view of the segment/geography values currently served by Node. */
public record CompanyRevenueMixLegacyRead(
        String note,
        List<Entry> segment,
        List<Entry> geography
) {
    public CompanyRevenueMixLegacyRead {
        segment = List.copyOf(Objects.requireNonNull(segment, "segment"));
        geography = List.copyOf(Objects.requireNonNull(geography, "geography"));
    }

    public record Entry(
            String label,
            BigDecimal value,
            String unit,
            BigDecimal percentOfTotal
    ) {
        public Entry {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
            label = label.trim();
        }
    }
}
