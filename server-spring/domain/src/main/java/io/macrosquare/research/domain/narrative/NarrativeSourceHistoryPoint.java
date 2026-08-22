package io.macrosquare.research.domain.narrative;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Transport-neutral audit projection of one immutable source observation.
 *
 * <p>Content hashes and object-storage keys deliberately stay out of this
 * projection. They are infrastructure evidence, not part of the research
 * domain contract exposed to clients.</p>
 */
public record NarrativeSourceHistoryPoint(
        String sourceKey,
        String label,
        LocalDate observationDate,
        Instant observedAt,
        int revision,
        NarrativeSourceQuality quality,
        NarrativeSourceStatus status,
        Double value,
        Double score,
        String detail,
        String sourceUrl
) {
    public NarrativeSourceHistoryPoint {
        if (sourceKey == null || sourceKey.isBlank()) throw new IllegalArgumentException("sourceKey is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (observationDate == null) throw new IllegalArgumentException("observationDate is required");
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
        if (quality == null) throw new IllegalArgumentException("quality is required");
        if (status == null || status == NarrativeSourceStatus.STALE) {
            throw new IllegalArgumentException("historical collector status cannot be STALE");
        }
        if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 10)) {
            throw new IllegalArgumentException("score must be between 0 and 10");
        }
        sourceKey = sourceKey.trim().toUpperCase(Locale.ROOT);
        label = label.trim();
        detail = detail == null ? "" : detail.trim();
        sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
    }
}
