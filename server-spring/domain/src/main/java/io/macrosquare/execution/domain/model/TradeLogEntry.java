package io.macrosquare.execution.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record TradeLogEntry(
        Instant timestamp,
        TradeLogKind kind,
        String asset,
        String from,
        String to,
        String notes,
        Boolean againstSystemRecommendation,
        Map<String, TradeLogValue> context
) {
    public TradeLogEntry {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        kind = Objects.requireNonNull(kind, "kind");
        asset = bounded(asset, 64, "asset");
        from = bounded(from, 256, "from");
        to = bounded(to, 256, "to");
        notes = bounded(notes, 4_000, "notes");
        if (context == null) {
            context = Map.of();
        } else {
            var copy = new LinkedHashMap<String, TradeLogValue>();
            if (context.size() > 64) throw new IllegalArgumentException("context has too many entries");
            context.forEach((key, value) -> copy.put(
                    boundedRequired(key, 128, "context key"),
                    Objects.requireNonNull(value, "context value")
            ));
            context = java.util.Collections.unmodifiableMap(copy);
        }
    }

    private static String boundedRequired(String value, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String bounded(String value, int maximum, String field) {
        if (value == null) return null;
        if (value.length() > maximum) throw new IllegalArgumentException(field + " is too long");
        return value;
    }
}
