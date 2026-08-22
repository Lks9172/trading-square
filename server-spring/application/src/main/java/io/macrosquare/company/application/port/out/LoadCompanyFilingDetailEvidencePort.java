package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyFilingDetailEvidence;

@FunctionalInterface
public interface LoadCompanyFilingDetailEvidencePort {
    CompanyFilingDetailEvidence load(String cik, String accessionNumber);
}
