package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;

/** Read-only company submissions source. */
public interface LoadCompanySubmissionsEvidencePort {

    CompanySubmissionsEvidence load(String cik);
}
