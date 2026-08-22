package io.macrosquare.company.application.model;

import java.time.LocalDate;

/** Transport-neutral independent market-cap observation. */
public record CompanyMarketCapitalization(
        String symbol,
        double value,
        LocalDate date,
        Double referencePrice
) {
    public CompanyMarketCapitalization {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("market capitalization must be positive and finite");
        }
        if (date == null) throw new IllegalArgumentException("market capitalization date is required");
        if (referencePrice != null && (!Double.isFinite(referencePrice) || referencePrice <= 0)) {
            throw new IllegalArgumentException("market capitalization reference price must be positive and finite");
        }
    }

    public CompanyMarketCapitalization(String symbol, double value, LocalDate date) {
        this(symbol, value, date, null);
    }

    public CompanyMarketCapitalization withReferencePrice(double price) {
        return new CompanyMarketCapitalization(symbol, value, date, price);
    }
}
