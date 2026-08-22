package io.macrosquare.research.domain.narrative;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

public record NarrativeSourceReading(
        NarrativeTheme theme,
        String sourceKey,
        String label,
        LocalDate observationDate,
        Instant observedAt,
        NarrativeSourceQuality quality,
        NarrativeSourceStatus status,
        Double value,
        double score,
        String detail,
        String sourceUrl,
        String contentHash,
        String rawObjectKey
) {
    public NarrativeSourceReading {
        if (theme == null) throw new IllegalArgumentException("theme is required");
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (observationDate == null) throw new IllegalArgumentException("observationDate is required");
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
        if (quality == null) throw new IllegalArgumentException("quality is required");
        if (status == null || status == NarrativeSourceStatus.STALE) {
            throw new IllegalArgumentException("collector status must be AVAILABLE, MISSING, or FAILED");
        }
        if (!Double.isFinite(score) || score < 0 || score > 10) {
            throw new IllegalArgumentException("score must be between 0 and 10");
        }
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        if (status == NarrativeSourceStatus.AVAILABLE && value == null) {
            throw new IllegalArgumentException("available reading requires value");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be lowercase SHA-256");
        }
        sourceKey = sourceKey.trim().toUpperCase(Locale.ROOT);
        label = label.trim();
        detail = detail == null ? "" : detail.trim();
        sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        rawObjectKey = rawObjectKey == null ? "" : rawObjectKey.trim();
    }
}
