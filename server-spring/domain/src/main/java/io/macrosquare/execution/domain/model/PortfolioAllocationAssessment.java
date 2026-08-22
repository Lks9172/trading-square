package io.macrosquare.execution.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Unit-aware interpretation of the user's persisted holdings.
 *
 * <p>The legacy plan contract documented percentages, while some production
 * records contain absolute KRW market values. Keeping the source values next
 * to the normalized percentages makes the migration lossless and prevents an
 * amount such as {@code 6_000_000} from being displayed as 6,000,000%.</p>
 */
public record PortfolioAllocationAssessment(
        SourceUnit sourceUnit,
        Map<String, Double> sourceValues,
        Map<String, Double> percentages,
        double sourceTotal,
        double denominator,
        double allocatedPct,
        double unallocatedPct,
        double overAllocatedPct,
        boolean normalized,
        List<String> cautions
) {
    public PortfolioAllocationAssessment {
        sourceUnit = Objects.requireNonNull(sourceUnit, "sourceUnit");
        sourceValues = immutable(sourceValues);
        percentages = immutable(percentages);
        requireFiniteNonNegative(sourceTotal, "sourceTotal");
        requireFiniteNonNegative(denominator, "denominator");
        requireFiniteNonNegative(allocatedPct, "allocatedPct");
        requireFiniteNonNegative(unallocatedPct, "unallocatedPct");
        requireFiniteNonNegative(overAllocatedPct, "overAllocatedPct");
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
    }

    public enum SourceUnit {
        EMPTY,
        PERCENT,
        KRW_ABSOLUTE
    }

    private static Map<String, Double> immutable(Map<String, Double> values) {
        if (values == null || values.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, Double>();
        values.forEach((key, value) -> {
            Objects.requireNonNull(key, "allocation key");
            requireFiniteNonNegative(value, "allocation value");
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
