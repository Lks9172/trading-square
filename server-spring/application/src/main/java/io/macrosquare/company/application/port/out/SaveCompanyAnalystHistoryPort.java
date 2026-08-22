package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;

import java.time.Instant;
import java.util.List;

/** Atomically replaces one ticker's application-owned analyst history. */
@FunctionalInterface
public interface SaveCompanyAnalystHistoryPort {

    void save(String normalizedTicker, List<CompanyAnalystHistoryPoint> history, Instant updatedAt);
}
