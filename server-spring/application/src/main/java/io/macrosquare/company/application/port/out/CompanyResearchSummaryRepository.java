package io.macrosquare.company.application.port.out;

import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;

import java.util.Map;
import java.util.Optional;

public interface CompanyResearchSummaryRepository {

    Optional<CompanyResearchSummarySnapshot> find(String normalizedTicker);

    /**
     * Loads the latest persisted row only for fail-closed quarantine during a
     * calculation-contract migration. Normal reads must continue using
     * {@link #find(String)} and must never expose an older version.
     */
    default Optional<CompanyResearchSummarySnapshot> findHistoricalForQuarantine(String normalizedTicker) {
        return find(normalizedTicker);
    }

    Map<String, CompanyResearchSummarySnapshot> findAll();

    void save(CompanyResearchSummarySnapshot snapshot);
}
