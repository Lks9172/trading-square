package io.macrosquare.disclosure.domain.model;

import java.time.Instant;
import java.util.List;

public record DartCompanySnapshot(
        String status,
        Instant asOf,
        DartCompany company,
        List<DartDisclosure> disclosures,
        List<DartFinancialMetric> financials,
        String methodology
) {
    public DartCompanySnapshot {
        status = status == null ? "collecting" : status;
        disclosures = List.copyOf(disclosures);
        financials = List.copyOf(financials);
        methodology = methodology == null ? "" : methodology;
    }
}
