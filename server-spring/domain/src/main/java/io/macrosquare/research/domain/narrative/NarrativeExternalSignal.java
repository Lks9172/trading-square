package io.macrosquare.research.domain.narrative;

import java.time.Instant;

public record NarrativeExternalSignal(
        String key,
        String label,
        Double value,
        double score,
        String detail,
        NarrativeSourceQuality quality,
        NarrativeSourceStatus status,
        Instant observedAt,
        Integer revision,
        double weight,
        String sourceUrl
) {
    public NarrativeExternalSignal(String key, String label, Double value, double score, String detail) {
        this(
                key, label, value, score, detail,
                NarrativeSourceQuality.LEGACY_UNKNOWN,
                value == null ? NarrativeSourceStatus.MISSING : NarrativeSourceStatus.AVAILABLE,
                null, null,
                value == null ? 0 : 1,
                ""
        );
    }

    public NarrativeExternalSignal {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (!Double.isFinite(score)) throw new IllegalArgumentException("score must be finite");
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        if (quality == null) throw new IllegalArgumentException("quality is required");
        if (status == null) throw new IllegalArgumentException("status is required");
        if (revision != null && revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (!Double.isFinite(weight) || weight < 0 || weight > 1) {
            throw new IllegalArgumentException("weight must be between 0 and 1");
        }
        key = key.trim();
        label = label.trim();
        detail = detail == null ? "" : detail;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
    }
}
