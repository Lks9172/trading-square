package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.CompanyFilingDocumentProbeReport;

public record CompanyFilingDocumentProbeResponse(
        String url,
        String format,
        Integer totalPages,
        Integer processedPages,
        int textCharacters,
        boolean hasText,
        boolean truncated,
        String preview,
        String summary
) {
    static CompanyFilingDocumentProbeResponse from(CompanyFilingDocumentProbeReport report) {
        return new CompanyFilingDocumentProbeResponse(
                report.url(),
                report.format().value(),
                report.totalPages(),
                report.processedPages(),
                report.textCharacters(),
                report.hasText(),
                report.truncated(),
                report.preview(),
                report.summary()
        );
    }
}
