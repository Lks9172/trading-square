package io.macrosquare.execution.domain.model;

public enum TradeLogKind {
    SIGNAL_CHANGE("signal_change"),
    ALLOCATION_CHANGE("allocation_change"),
    USER_ACTION("user_action"),
    OBSERVATION("observation");

    private final String value;

    TradeLogKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static TradeLogKind from(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("kind required");
        for (var candidate : values()) {
            if (candidate.value.equals(value.trim())) return candidate;
        }
        throw new IllegalArgumentException("unsupported trade log kind");
    }
}
