package io.macrosquare.technical.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Provider-neutral closing price used by cross-asset technical policies. */
public record TechnicalClosePoint(LocalDate date, double close) {
    public TechnicalClosePoint {
        Objects.requireNonNull(date, "date");
        if (!Double.isFinite(close) || close <= 0) {
            throw new IllegalArgumentException("close must be positive and finite");
        }
    }
}
