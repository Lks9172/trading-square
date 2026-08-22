package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;

public record SectorPricePoint(LocalDate observedOn, double close) {
    public SectorPricePoint {
        Objects.requireNonNull(observedOn, "observedOn");
        if (!Double.isFinite(close) || close <= 0) {
            throw new IllegalArgumentException("close must be positive and finite");
        }
    }
}
