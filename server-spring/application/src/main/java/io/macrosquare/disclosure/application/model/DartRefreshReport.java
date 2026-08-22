package io.macrosquare.disclosure.application.model;

import java.time.Instant;
import java.util.List;

public record DartRefreshReport(
        Instant startedAt,
        Instant completedAt,
        int companyCount,
        int disclosureCount,
        int financialMetricCount,
        List<String> failures
) {
    public DartRefreshReport {
        failures = List.copyOf(failures);
    }
}
