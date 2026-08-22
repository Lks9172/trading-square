package io.macrosquare.company.application.model;

import java.time.LocalDate;

/**
 * Transport-neutral market quote used as an application input. A legacy
 * projection may represent an unavailable quote with a null price/date pair;
 * direct quote ports must return an available value.
 */
public record CompanyMarketQuote(
        String symbol,
        Double price,
        LocalDate date
) {
    public CompanyMarketQuote {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("quote symbol is required");
        if (price == null && date != null) {
            throw new IllegalArgumentException("quote date requires a price");
        }
        if (price != null) {
            if (!Double.isFinite(price) || price <= 0) {
                throw new IllegalArgumentException("quote price must be positive and finite");
            }
            if (date == null) throw new IllegalArgumentException("available quote requires a date");
        }
    }

    public boolean available() {
        return price != null;
    }
}
