package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;

/** Loads bounded, transport-neutral content from a validated SEC filing URL. */
@FunctionalInterface
public interface LoadCompanyFilingDocumentContentPort {
    CompanyFilingDocumentContent loadContent(String sourceUrl);
}
