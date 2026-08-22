package io.macrosquare.institutional.domain.model;

import java.util.List;

public record InstitutionalConsensus(
        String cusip,
        String issuer,
        String titleClass,
        int managerCount,
        List<String> managers,
        double totalValueUsd,
        double netValueDeltaUsd,
        double estimatedNetFlowUsd,
        InstitutionalSecurityIdentity identity
) {
    public InstitutionalConsensus {
        managers = List.copyOf(managers);
    }
}
