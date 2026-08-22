package io.macrosquare.company.application.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Transport-neutral normalized company profile and recent filing metadata.
 */
public record CompanySubmissionsSnapshot(
        Profile profile,
        List<Filing> filings
) {
    public CompanySubmissionsSnapshot {
        profile = Objects.requireNonNull(profile, "profile");
        filings = List.copyOf(Objects.requireNonNull(filings, "filings"));
    }

    public record Profile(
            String ticker,
            String cik,
            String name,
            String exchange,
            String sic
    ) {
        public Profile {
            ticker = requireText(ticker, "ticker");
            if (cik == null || !cik.matches("\\d{10}")) {
                throw new IllegalArgumentException("cik must contain exactly ten digits");
            }
            name = requireText(name, "name");
        }
    }

    public record Filing(
            String accessionNumber,
            LocalDate filingDate,
            String form,
            String primaryDocument,
            String primaryDocumentDescription,
            boolean earningsRelated,
            String filingUrl
    ) {
        public Filing {
            accessionNumber = requireText(accessionNumber, "accessionNumber");
            filingDate = Objects.requireNonNull(filingDate, "filingDate");
            form = requireText(form, "form");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
