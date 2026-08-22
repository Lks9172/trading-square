package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.Objects;

public record BottomPatternPoint(LocalDate date, double close, Double volume, Double high, Double low) {
    public BottomPatternPoint {
        Objects.requireNonNull(date, "date must not be null");
        if (!Double.isFinite(close) || close <= 0) {
            throw new IllegalArgumentException("close must be positive and finite");
        }
        if (volume != null && (!Double.isFinite(volume) || volume < 0)) {
            throw new IllegalArgumentException("volume must be non-negative and finite");
        }
        if (high != null && (!Double.isFinite(high) || high <= 0)) {
            throw new IllegalArgumentException("high must be positive and finite");
        }
        if (low != null && (!Double.isFinite(low) || low <= 0)) {
            throw new IllegalArgumentException("low must be positive and finite");
        }
        if (high != null && low != null && high < low) {
            throw new IllegalArgumentException("high must be greater than or equal to low");
        }
    }

    public BottomPatternPoint(LocalDate date, double close, Double volume) {
        this(date, close, volume, null, null);
    }
}
