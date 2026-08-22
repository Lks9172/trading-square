package io.macrosquare.institutional.application.port.out;

import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityObservation;

import java.util.List;

@FunctionalInterface
public interface ResolveInstitutionalSecurityIdentitiesPort {
    List<InstitutionalSecurityIdentity> resolve(List<InstitutionalSecurityObservation> observations);
}
