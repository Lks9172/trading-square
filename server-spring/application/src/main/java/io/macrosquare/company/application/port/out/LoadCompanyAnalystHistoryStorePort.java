package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;

import java.util.List;
import java.util.Optional;

/** Reads the application-owned analyst-history store. */
@FunctionalInterface
public interface LoadCompanyAnalystHistoryStorePort {

    Optional<List<CompanyAnalystHistoryPoint>> load(String normalizedTicker);
}
