package io.macrosquare.integrity.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, storage-neutral evidence loaded from authoritative projections. */
public record DataIntegrityEvidence(
        Map<IntegrityMetric, Long> metrics,
        Instant oldestCompanySummaryAt,
        Instant observedAt,
        List<String> hardCollectionSources
) {
    public DataIntegrityEvidence {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(observedAt, "observedAt");
        var normalized = new EnumMap<IntegrityMetric, Long>(IntegrityMetric.class);
        normalized.putAll(metrics);
        if (!normalized.keySet().containsAll(java.util.EnumSet.allOf(IntegrityMetric.class))) {
            throw new IllegalArgumentException("every integrity metric must be supplied");
        }
        normalized.forEach((key, value) -> {
            if (value == null || value < 0) {
                throw new IllegalArgumentException("integrity metrics must be non-negative: " + key);
            }
        });
        metrics = Map.copyOf(normalized);
        hardCollectionSources = List.copyOf(
                hardCollectionSources == null ? List.of() : hardCollectionSources);
    }

    public long metric(IntegrityMetric metric) {
        return metrics.get(Objects.requireNonNull(metric));
    }
}
