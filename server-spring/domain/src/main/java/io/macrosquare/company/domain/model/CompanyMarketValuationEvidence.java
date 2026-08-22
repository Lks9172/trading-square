package io.macrosquare.company.domain.model;

import java.time.LocalDate;

/**
 * Market evidence used to put filing facts and market prices on one valuation basis.
 *
 * <p>The independent market capitalization is intentionally separate from SEC
 * shares outstanding. SEC point-in-time shares can remain on a pre-split basis
 * until the next filing, while market prices are adjusted immediately.</p>
 */
public record CompanyMarketValuationEvidence(
        Double currentPrice,
        LocalDate quoteDate,
        Double independentMarketCap,
        LocalDate marketCapDate,
        Double marketCapReferencePrice
) {
    public CompanyMarketValuationEvidence {
        requirePair(currentPrice, quoteDate, "current price", "quote date");
        requirePair(independentMarketCap, marketCapDate, "market capitalization", "market capitalization date");
        requirePositive(currentPrice, "current price");
        requirePositive(independentMarketCap, "market capitalization");
        requirePositive(marketCapReferencePrice, "market capitalization reference price");
        if (marketCapReferencePrice != null && independentMarketCap == null) {
            throw new IllegalArgumentException("market capitalization is required with its reference price");
        }
    }

    public CompanyMarketValuationEvidence(
            Double currentPrice,
            LocalDate quoteDate,
            Double independentMarketCap,
            LocalDate marketCapDate
    ) {
        this(currentPrice, quoteDate, independentMarketCap, marketCapDate, null);
    }

    public static CompanyMarketValuationEvidence quoteOnly(Double currentPrice, LocalDate quoteDate) {
        return new CompanyMarketValuationEvidence(currentPrice, quoteDate, null, null, null);
    }

    private static void requirePair(Object value, Object date, String valueName, String dateName) {
        if ((value == null) != (date == null)) {
            throw new IllegalArgumentException(valueName + " and " + dateName + " must be supplied together");
        }
    }

    private static void requirePositive(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value <= 0)) {
            throw new IllegalArgumentException(field + " must be positive and finite");
        }
    }
}
