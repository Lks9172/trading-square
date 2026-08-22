package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.Objects;

public record VolumePricePoint(
        LocalDate date,
        Double vwap20,
        Double obvPressure20Pct
) {
    public VolumePricePoint {
        Objects.requireNonNull(date, "date must not be null");
        finite(vwap20, "vwap20");
        finite(obvPressure20Pct, "obvPressure20Pct");
    }

    private static void finite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
