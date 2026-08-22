package io.macrosquare.company.application.port.in;

@FunctionalInterface
public interface ProbeCompanyFilingDocumentUseCase {
    CompanyFilingDocumentProbeReport probe(String sourceUrl);
}
