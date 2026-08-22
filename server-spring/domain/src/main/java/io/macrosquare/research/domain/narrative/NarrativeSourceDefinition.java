package io.macrosquare.research.domain.narrative;

import java.time.Duration;

public record NarrativeSourceDefinition(
        String key,
        String label,
        NarrativeSourceQuality quality,
        Duration staleAfter,
        Duration maximumFallbackAge
) {
    public NarrativeSourceDefinition {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (quality == null) throw new IllegalArgumentException("quality is required");
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        if (maximumFallbackAge == null || maximumFallbackAge.compareTo(staleAfter) < 0) {
            throw new IllegalArgumentException("maximumFallbackAge must be at least staleAfter");
        }
        key = key.trim().toUpperCase(java.util.Locale.ROOT);
        label = label.trim();
    }
}
