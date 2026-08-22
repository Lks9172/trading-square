package io.macrosquare.market.domain.calendar;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Provider-independent market event.  Calendar events are volatility context,
 * never directional return signals.
 */
public record MarketCalendarEvent(
        LocalDate date,
        String name,
        String category,
        Importance importance,
        Origin origin,
        boolean estimated
) {
    public MarketCalendarEvent {
        date = Objects.requireNonNull(date, "date");
        name = requireText(name, "name");
        category = requireText(category, "category");
        importance = Objects.requireNonNull(importance, "importance");
        origin = Objects.requireNonNull(origin, "origin");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.strip();
    }

    public enum Importance {
        HIGH,
        MEDIUM
    }

    public enum Origin {
        PERSISTED_SOURCE,
        SPRING_RULE
    }
}
