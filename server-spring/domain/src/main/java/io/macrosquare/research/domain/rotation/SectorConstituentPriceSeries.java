package io.macrosquare.research.domain.rotation;

import java.util.Comparator;
import java.util.List;

public record SectorConstituentPriceSeries(String ticker, List<SectorPricePoint> points) {
    public SectorConstituentPriceSeries {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        ticker = ticker.trim().toUpperCase(java.util.Locale.ROOT);
        points = List.copyOf(points == null ? List.of() : points.stream()
                .sorted(Comparator.comparing(SectorPricePoint::observedOn)).toList());
        for (var index = 1; index < points.size(); index++) {
            if (!points.get(index - 1).observedOn().isBefore(points.get(index).observedOn())) {
                throw new IllegalArgumentException("price dates must be unique");
            }
        }
    }
}
