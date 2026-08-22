package io.macrosquare.company.domain.model;

/**
 * Transport-neutral document metadata parsed from one SEC filing index.
 */
public record CompanyFilingDocumentEvidence(
        int sequence,
        String description,
        String documentName,
        String documentType,
        Long sizeBytes,
        String sourceUrl
) {
    public CompanyFilingDocumentEvidence {
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        documentName = requireText(documentName, "documentName");
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        sourceUrl = requireText(sourceUrl, "sourceUrl");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
