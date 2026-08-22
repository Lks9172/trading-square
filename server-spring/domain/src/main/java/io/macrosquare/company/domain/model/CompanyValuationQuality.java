package io.macrosquare.company.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Audit trail for market-cap/enterprise-value normalization. */
public record CompanyValuationQuality(
        MarketCapitalizationBasis basis,
        LocalDate marketCapAsOf,
        LocalDate secSharesAsOf,
        Double rawSecShares,
        Double resolvedShares,
        Double sharesDivergencePct,
        Double detectedSplitFactor,
        boolean valuationEligible,
        List<String> warnings
) {
    public CompanyValuationQuality {
        Objects.requireNonNull(basis, "basis");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (valuationEligible && basis == MarketCapitalizationBasis.UNAVAILABLE) {
            throw new IllegalArgumentException("unavailable valuation cannot be eligible");
        }
    }

    public static CompanyValuationQuality unavailable(String warning) {
        return new CompanyValuationQuality(
                MarketCapitalizationBasis.UNAVAILABLE,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                warning == null || warning.isBlank() ? List.of() : List.of(warning)
        );
    }

    public enum MarketCapitalizationBasis {
        INDEPENDENT_MARKET_CAP,
        SEC_SHARES,
        UNAVAILABLE
    }
}
