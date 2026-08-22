package io.macrosquare.research.domain.bottleneck;

import java.util.Locale;

public record BottleneckEvidence(
        String ticker,
        String company,
        String corpus,
        int totalScore,
        Double revenueGrowthYoY,
        Double operatingMargin,
        Double evToSales
) {
    public BottleneckEvidence {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (company == null || company.isBlank()) throw new IllegalArgumentException("company is required");
        if (totalScore < 0 || totalScore > 100) throw new IllegalArgumentException("totalScore must be between 0 and 100");
        requireFinite(revenueGrowthYoY, "revenueGrowthYoY");
        requireFinite(operatingMargin, "operatingMargin");
        requireFinite(evToSales, "evToSales");
        ticker = ticker.trim().toUpperCase(Locale.ROOT);
        company = company.trim();
        corpus = corpus == null ? "" : corpus;
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
