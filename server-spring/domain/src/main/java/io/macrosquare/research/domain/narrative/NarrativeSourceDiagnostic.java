package io.macrosquare.research.domain.narrative;

import java.time.Instant;

public record NarrativeSourceDiagnostic(
        String sourceKey,
        String label,
        NarrativeSourceQuality quality,
        NarrativeSourceStatus status,
        Instant latestObservedAt,
        Instant lastAvailableAt,
        Long ageHours,
        Integer revision,
        int missingStreak,
        Double value,
        Double score,
        String detail,
        String sourceUrl,
        double effectiveWeight
) {
    public NarrativeSourceDiagnostic {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (quality == null) throw new IllegalArgumentException("quality is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (ageHours != null && ageHours < 0) throw new IllegalArgumentException("ageHours must not be negative");
        if (revision != null && revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (missingStreak < 0) throw new IllegalArgumentException("missingStreak must not be negative");
        if (effectiveWeight < 0 || effectiveWeight > 1 || !Double.isFinite(effectiveWeight)) {
            throw new IllegalArgumentException("effectiveWeight must be between 0 and 1");
        }
        detail = detail == null ? "" : detail;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }
}
