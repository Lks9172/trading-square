package io.macrosquare.company.application.port.out;

import io.macrosquare.company.domain.model.CompanyAnalystConsensus;

/** Loads the current analyst consensus without exposing its HTTP or cache source. */
@FunctionalInterface
public interface LoadCompanyAnalystConsensusPort {

    CompanyAnalystConsensus load(String normalizedTicker);
}
