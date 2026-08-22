package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Framework-free evidence returned by a company submissions source.
 */
public record CompanySubmissionsEvidence(
        String cik,
        String name,
        List<String> tickers,
        List<String> exchanges,
        String sic,
        List<CompanyFilingEvidence> filings
) {
    public CompanySubmissionsEvidence {
        if (cik == null || !cik.matches("\\d{10}")) {
            throw new IllegalArgumentException("cik must contain exactly ten digits");
        }
        name = Objects.requireNonNull(name, "name");
        tickers = List.copyOf(Objects.requireNonNull(tickers, "tickers"));
        exchanges = List.copyOf(Objects.requireNonNull(exchanges, "exchanges"));
        filings = List.copyOf(Objects.requireNonNull(filings, "filings"));
    }
}
