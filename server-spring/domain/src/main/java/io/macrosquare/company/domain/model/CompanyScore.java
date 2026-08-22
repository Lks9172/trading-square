package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;

public record CompanyScore(
        Ticker ticker,
        int totalScore,
        ScoreBreakdown growth,
        ScoreBreakdown quality,
        ScoreBreakdown valuation,
        ScoreBreakdown balanceSheet,
        List<String> reasons
) {
    public CompanyScore {
        Objects.requireNonNull(ticker, "ticker must not be null");
        Objects.requireNonNull(growth, "growth must not be null");
        Objects.requireNonNull(quality, "quality must not be null");
        Objects.requireNonNull(valuation, "valuation must not be null");
        Objects.requireNonNull(balanceSheet, "balanceSheet must not be null");
        if (totalScore < 0 || totalScore > 100) {
            throw new IllegalArgumentException("totalScore must be between 0 and 100");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
