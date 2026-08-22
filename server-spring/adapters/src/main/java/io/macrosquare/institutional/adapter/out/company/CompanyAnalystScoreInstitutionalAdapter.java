package io.macrosquare.institutional.adapter.out.company;

import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.institutional.application.port.out.LoadInstitutionalAnalystScorePort;

import java.util.Objects;

/** Outer-layer anti-corruption adapter; no company type crosses into the institutional context. */
public final class CompanyAnalystScoreInstitutionalAdapter implements LoadInstitutionalAnalystScorePort {

    private final LoadCompanyAnalystConsensusPort companyConsensus;

    public CompanyAnalystScoreInstitutionalAdapter(LoadCompanyAnalystConsensusPort companyConsensus) {
        this.companyConsensus = Objects.requireNonNull(companyConsensus);
    }

    @Override
    public Double load(String ticker) {
        var value = companyConsensus.load(ticker);
        return value == null ? null : value.analystScore();
    }
}
