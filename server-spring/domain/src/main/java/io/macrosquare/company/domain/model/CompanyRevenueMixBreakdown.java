package io.macrosquare.company.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** A validated, percentage-normalized segment or geography revenue breakdown. */
public record CompanyRevenueMixBreakdown(
        Category category,
        CompanyRevenueMixDimension dimension,
        String dimensionName,
        LocalDate periodStart,
        LocalDate periodEnd,
        String unit,
        BigDecimal consolidatedTotal,
        BigDecimal selectedTotal,
        BigDecimal coveragePercent,
        String source,
        List<CompanyRevenueMixEntry> entries
) {
    public CompanyRevenueMixBreakdown {
        category = Objects.requireNonNull(category, "category");
        dimension = Objects.requireNonNull(dimension, "dimension");
        if (dimensionName == null || dimensionName.isBlank()) {
            throw new IllegalArgumentException("dimensionName is required");
        }
        dimensionName = dimensionName.trim();
        periodStart = Objects.requireNonNull(periodStart, "periodStart");
        periodEnd = Objects.requireNonNull(periodEnd, "periodEnd");
        if (periodStart.isAfter(periodEnd)) throw new IllegalArgumentException("periodStart must not exceed periodEnd");
        if (unit == null || unit.isBlank()) throw new IllegalArgumentException("unit is required");
        unit = unit.trim();
        consolidatedTotal = Objects.requireNonNull(consolidatedTotal, "consolidatedTotal");
        selectedTotal = Objects.requireNonNull(selectedTotal, "selectedTotal");
        coveragePercent = Objects.requireNonNull(coveragePercent, "coveragePercent");
        if (consolidatedTotal.signum() <= 0 || selectedTotal.signum() <= 0) {
            throw new IllegalArgumentException("totals must be positive");
        }
        source = source == null || source.isBlank()
                ? throwMissingSource()
                : source.trim();
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        if (entries.size() < 2) throw new IllegalArgumentException("a revenue mix needs at least two entries");
    }

    private static String throwMissingSource() {
        throw new IllegalArgumentException("source is required");
    }

    public enum Category {
        SEGMENT("segment"),
        GEOGRAPHY("geography");

        private final String value;

        Category(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
