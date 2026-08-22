package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;

public record SectorTotalReturnPoint(LocalDate date, double value) {
    public SectorTotalReturnPoint {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("total-return index value must be finite and positive");
        }
    }
}
