package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;

/** Read-only port for semantic revenue dimensions parsed from an official filing. */
public interface LoadCompanyRevenueMixEvidencePort {
    CompanyRevenueMixEvidence loadRevenueMix(String source);
}
