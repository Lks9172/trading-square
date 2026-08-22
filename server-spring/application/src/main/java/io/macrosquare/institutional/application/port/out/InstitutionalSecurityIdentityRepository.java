package io.macrosquare.institutional.application.port.out;

import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface InstitutionalSecurityIdentityRepository {
    int savePointInTime(List<InstitutionalSecurityIdentity> identities);

    Map<String, InstitutionalSecurityIdentity> loadActiveOn(LocalDate reportPeriod);
}
