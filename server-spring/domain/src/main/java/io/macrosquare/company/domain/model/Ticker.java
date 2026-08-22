package io.macrosquare.company.domain.model;

import java.util.Locale;
import java.util.Objects;

public record Ticker(String value) {

    public Ticker {
        Objects.requireNonNull(value, "ticker must not be null");
        value = value.trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() || !value.matches("[A-Z0-9.^-]{1,15}")) {
            throw new IllegalArgumentException("invalid ticker: " + value);
        }
    }
}
