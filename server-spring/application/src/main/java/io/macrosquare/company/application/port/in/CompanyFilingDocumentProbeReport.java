package io.macrosquare.company.application.port.in;

import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;

/** Bounded internal migration evidence; the complete extracted body is never returned. */
public record CompanyFilingDocumentProbeReport(
        String url,
        CompanyFilingDocumentContent.Format format,
        Integer totalPages,
        Integer processedPages,
        int textCharacters,
        boolean hasText,
        boolean truncated,
        String preview,
        String summary
) {
    public CompanyFilingDocumentProbeReport {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        if (format == null) throw new IllegalArgumentException("format is required");
        if (textCharacters < 0) throw new IllegalArgumentException("textCharacters must not be negative");
        if (preview == null) throw new IllegalArgumentException("preview is required");
    }
}
