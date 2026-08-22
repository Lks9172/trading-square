package io.macrosquare.company.application.port.out;

import io.macrosquare.company.application.model.CompanyIdentity;

public interface ResolveCompanyIdentityPort {
    CompanyIdentity resolve(String normalizedTicker);
}
