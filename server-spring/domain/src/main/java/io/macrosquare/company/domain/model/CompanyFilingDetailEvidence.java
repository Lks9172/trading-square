package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Framework-free evidence obtained from an SEC accession index.
 */
public record CompanyFilingDetailEvidence(
        String cik,
        String accessionNumber,
        String indexUrl,
        List<CompanyFilingDocumentEvidence> documents
) {
    public CompanyFilingDetailEvidence {
        if (cik == null || !cik.matches("\\d{10}")) {
            throw new IllegalArgumentException("cik must contain exactly ten digits");
        }
        if (accessionNumber == null || !accessionNumber.matches("\\d{10}-\\d{2}-\\d{6}")) {
            throw new IllegalArgumentException("accessionNumber has an invalid SEC format");
        }
        if (indexUrl == null || indexUrl.isBlank()) throw new IllegalArgumentException("indexUrl is required");
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }
}
