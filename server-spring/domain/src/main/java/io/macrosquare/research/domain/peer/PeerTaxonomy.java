package io.macrosquare.research.domain.peer;

import java.time.LocalDate;

/** Point-in-time SEC taxonomy for one exchange-listed issuer. */
public record PeerTaxonomy(
        String ticker,
        String cik,
        String companyName,
        int sic,
        String sicDescription,
        String sectorKey,
        LocalDate validFrom,
        LocalDate validTo
) {
    public PeerTaxonomy {
        ticker = required(ticker, "ticker").toUpperCase(java.util.Locale.ROOT);
        cik = required(cik, "cik");
        companyName = required(companyName, "companyName");
        if (sic < 100 || sic > 9999) throw new IllegalArgumentException("sic must contain 3-4 digits");
        sicDescription = required(sicDescription, "sicDescription");
        sectorKey = required(sectorKey, "sectorKey");
        if (validFrom == null) throw new IllegalArgumentException("validFrom is required");
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo must not precede validFrom");
        }
    }

    public boolean activeOn(LocalDate date) {
        return date != null && !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
