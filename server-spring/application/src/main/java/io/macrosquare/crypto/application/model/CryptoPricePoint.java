package io.macrosquare.crypto.application.model;

import java.time.LocalDate;
import java.util.Objects;

/** Immutable price-series point published by the Crypto context's outbound port. */
public record CryptoPricePoint(LocalDate date, double value) {
    public CryptoPricePoint {
        Objects.requireNonNull(date, "date must not be null");
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("value must be finite and positive");
        }
    }
}
