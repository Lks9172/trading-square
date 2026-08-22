package io.macrosquare.institutional.domain.model;

public record InstitutionalHolding(
        String cusip,
        String issuer,
        String titleClass,
        String putCall,
        double valueUsd,
        double shares
) {
    public InstitutionalHolding {
        if (cusip == null || cusip.isBlank()) throw new IllegalArgumentException("CUSIP is required");
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        titleClass = titleClass == null ? "" : titleClass.trim();
        putCall = putCall == null ? "" : putCall.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Double.isFinite(valueUsd) || valueUsd <= 0) {
            throw new IllegalArgumentException("valueUsd must be positive and finite");
        }
        if (!Double.isFinite(shares) || shares <= 0) {
            throw new IllegalArgumentException("shares must be positive and finite");
        }
    }

    public String positionKey() {
        return cusip + "|" + titleClass + "|" + putCall;
    }
}
