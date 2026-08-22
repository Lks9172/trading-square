package io.macrosquare.institutional.domain.model;

import java.time.LocalDate;

/** A CUSIP/issuer pair observed in a specific 13F report period. */
public record InstitutionalSecurityObservation(String cusip, String issuer, LocalDate reportPeriod) {
    public InstitutionalSecurityObservation {
        if (cusip == null || cusip.isBlank()) throw new IllegalArgumentException("cusip is required");
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        if (reportPeriod == null) throw new IllegalArgumentException("reportPeriod is required");
        cusip = cusip.trim().toUpperCase(java.util.Locale.ROOT);
        issuer = issuer.trim();
    }
}
