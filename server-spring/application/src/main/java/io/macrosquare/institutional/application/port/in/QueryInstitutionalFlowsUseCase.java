package io.macrosquare.institutional.application.port.in;

import io.macrosquare.institutional.domain.model.InstitutionalFlowSnapshot;

public interface QueryInstitutionalFlowsUseCase {
    InstitutionalFlowSnapshot query();
}
