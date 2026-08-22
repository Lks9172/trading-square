package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;

import java.util.List;

/** Loads immutable cutover evidence used only to seed or compare the owned store. */
@FunctionalInterface
public interface LoadCompanyAnalystHistorySeedPort {

    List<CompanyAnalystHistoryPoint> load(String normalizedTicker);
}
