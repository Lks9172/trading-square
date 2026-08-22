package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;

/** Resolves the migration-safe analyst-history source for calculations. */
@FunctionalInterface
public interface ResolveCompanyAnalystHistoryUseCase {

    CompanyAnalystHistoryRead resolve(String ticker);
}
