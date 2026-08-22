package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanyReadModels.Research;

/** Replaces refreshable company evidence while retaining the complete public projection. */
public interface EnrichCompanyResearchUseCase {
    Research enrich(String ticker, Research baseline);
}
