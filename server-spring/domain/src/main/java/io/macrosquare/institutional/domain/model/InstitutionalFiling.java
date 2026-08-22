package io.macrosquare.institutional.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record InstitutionalFiling(
        InstitutionalManager manager,
        String accessionNumber,
        LocalDate filedOn,
        LocalDate reportPeriod,
        String sourceUrl,
        String rawObjectKey,
        List<InstitutionalHolding> holdings
) {
    public InstitutionalFiling {
        Objects.requireNonNull(manager, "manager");
        if (accessionNumber == null || !accessionNumber.matches("\\d{10}-\\d{2}-\\d{6}")) {
            throw new IllegalArgumentException("invalid accession number");
        }
        Objects.requireNonNull(filedOn, "filedOn");
        Objects.requireNonNull(reportPeriod, "reportPeriod");
        if (reportPeriod.isAfter(filedOn)) {
            throw new IllegalArgumentException("reportPeriod must not be after filedOn");
        }
        if (sourceUrl == null || sourceUrl.isBlank()) throw new IllegalArgumentException("sourceUrl is required");
        rawObjectKey = rawObjectKey == null ? "" : rawObjectKey;
        holdings = List.copyOf(Objects.requireNonNull(holdings, "holdings"));
    }
}
