package io.macrosquare.execution.domain.model;

public enum InvestmentHorizon {
    SHORT("short"),
    MEDIUM("medium"),
    LONG("long");

    private final String value;

    InvestmentHorizon(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static InvestmentHorizon from(String value) {
        if (value == null) throw new IllegalArgumentException("horizon is required");
        for (var candidate : values()) {
            if (candidate.value.equalsIgnoreCase(value.trim())) return candidate;
        }
        throw new IllegalArgumentException("horizon must be short, medium, or long");
    }
}
