package io.macrosquare.company.application.service;

import io.macrosquare.company.application.port.in.CompanyFilingDocumentProbeReport;
import io.macrosquare.company.application.port.in.ProbeCompanyFilingDocumentUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDocumentContentPort;
import io.macrosquare.company.domain.service.CompanyIrMaterialPolicy;

import java.util.Objects;

/** Read-only migration probe that exposes extraction diagnostics without returning the full body. */
public final class ProbeCompanyFilingDocumentService implements ProbeCompanyFilingDocumentUseCase {

    private static final int PREVIEW_LIMIT = 500;

    private final LoadCompanyFilingDocumentContentPort contentPort;
    private final CompanyIrMaterialPolicy materialPolicy;

    public ProbeCompanyFilingDocumentService(
            LoadCompanyFilingDocumentContentPort contentPort,
            CompanyIrMaterialPolicy materialPolicy
    ) {
        this.contentPort = Objects.requireNonNull(contentPort, "contentPort");
        this.materialPolicy = Objects.requireNonNull(materialPolicy, "materialPolicy");
    }

    @Override
    public CompanyFilingDocumentProbeReport probe(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("url is required");
        var content = contentPort.loadContent(sourceUrl);
        var preview = content.text().length() <= PREVIEW_LIMIT
                ? content.text()
                : content.text().substring(0, PREVIEW_LIMIT);
        return new CompanyFilingDocumentProbeReport(
                sourceUrl,
                content.format(),
                content.totalPages(),
                content.processedPages(),
                content.textCharacters(),
                content.hasText(),
                content.truncated(),
                preview,
                materialPolicy.summarize(content.text()).orElse(null)
        );
    }
}
