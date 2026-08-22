package io.macrosquare.company.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Transport-neutral filing metadata observed from a company submissions source.
 */
public record CompanyFilingEvidence(
        String accessionNumber,
        LocalDate filingDate,
        LocalDate reportDate,
        String form,
        String primaryDocument,
        String primaryDocumentDescription,
        String items,
        String sourceUrl
) {
    public CompanyFilingEvidence {
        accessionNumber = requireText(accessionNumber, "accessionNumber");
        filingDate = Objects.requireNonNull(filingDate, "filingDate");
        form = requireText(form, "form");
    }

    public CompanyFilingEvidence(
            String accessionNumber,
            LocalDate filingDate,
            String form,
            String primaryDocument,
            String primaryDocumentDescription,
            String items,
            String sourceUrl
    ) {
        this(
                accessionNumber,
                filingDate,
                null,
                form,
                primaryDocument,
                primaryDocumentDescription,
                items,
                sourceUrl
        );
    }

    public CompanyFilingEvidence(
            String accessionNumber,
            LocalDate filingDate,
            String form,
            String primaryDocument,
            String primaryDocumentDescription,
            String sourceUrl
    ) {
        this(
                accessionNumber,
                filingDate,
                null,
                form,
                primaryDocument,
                primaryDocumentDescription,
                null,
                sourceUrl
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
