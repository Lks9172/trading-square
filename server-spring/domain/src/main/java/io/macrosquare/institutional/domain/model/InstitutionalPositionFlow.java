package io.macrosquare.institutional.domain.model;

public record InstitutionalPositionFlow(
        String cusip,
        String issuer,
        String titleClass,
        String putCall,
        double currentValueUsd,
        double previousValueUsd,
        double valueDeltaUsd,
        Double valueDeltaPct,
        double currentShares,
        double previousShares,
        double shareDelta,
        Double shareDeltaPct,
        double estimatedNetFlowUsd,
        InstitutionalFlowAction action,
        InstitutionalSecurityIdentity identity
) {
}
