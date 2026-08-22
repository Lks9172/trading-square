package io.macrosquare.institutional.domain.model;

import java.time.LocalDate;

/** Point-in-time identity resolved for a reported 13F CUSIP. */
public record InstitutionalSecurityIdentity(
        String cusip,
        String ticker,
        String cik,
        String issuer,
        String sectorKey,
        LocalDate validFrom,
        LocalDate validTo,
        int confidence,
        String source
) {
    public InstitutionalSecurityIdentity {
        cusip = required(cusip, "cusip").toUpperCase(java.util.Locale.ROOT);
        ticker = required(ticker, "ticker").toUpperCase(java.util.Locale.ROOT);
        cik = cik == null ? "" : cik.trim();
        issuer = required(issuer, "issuer");
        sectorKey = sectorKey == null ? "" : sectorKey.trim();
        if (validFrom == null) throw new IllegalArgumentException("validFrom is required");
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo must not precede validFrom");
        }
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence must be between 0 and 100");
        }
        source = required(source, "source");
    }

    public boolean activeOn(LocalDate date) {
        return date != null && !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
