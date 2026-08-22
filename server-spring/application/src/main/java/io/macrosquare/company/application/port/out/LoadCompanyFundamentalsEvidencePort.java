package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;

public interface LoadCompanyFundamentalsEvidencePort {
    CompanyFundamentalsEvidence load(String cik);
}
