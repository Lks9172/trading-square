package io.macrosquare.company.domain.horizon;

import java.time.LocalDate;
import java.util.List;

public record CompanyWalkForwardValidation(
        LocalDate firstDate,
        LocalDate lastDate,
        int historyPointCount,
        String methodology,
        List<HorizonWalkForwardMetric> horizons
) {
    public CompanyWalkForwardValidation {
        if (historyPointCount < 0) throw new IllegalArgumentException("historyPointCount must be non-negative");
        if (methodology == null || methodology.isBlank()) throw new IllegalArgumentException("methodology is required");
        horizons = List.copyOf(horizons);
    }
}
