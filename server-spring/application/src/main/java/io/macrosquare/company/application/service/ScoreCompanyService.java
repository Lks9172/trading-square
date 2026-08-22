package io.macrosquare.company.application.service;

import io.macrosquare.company.application.port.in.ScoreCompanyCommand;
import io.macrosquare.company.application.port.in.ScoreCompanyUseCase;
import io.macrosquare.company.domain.model.CompanyFinancials;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.Ticker;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;

import java.util.Objects;

public final class ScoreCompanyService implements ScoreCompanyUseCase {

    private final CompanyScoringPolicy scoringPolicy;

    public ScoreCompanyService(CompanyScoringPolicy scoringPolicy) {
        this.scoringPolicy = Objects.requireNonNull(scoringPolicy);
    }

    @Override
    public CompanyScore score(ScoreCompanyCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        var financials = new CompanyFinancials(
                new Ticker(command.ticker()),
                command.revenueGrowthYoY(),
                command.operatingMargin(),
                command.freeCashFlowMargin(),
                command.roe(),
                command.operatingMarginTrend(),
                command.evToSales(),
                command.evToFcf(),
                command.netDebtToRevenue(),
                command.cash(),
                command.debt(),
                command.shareDilutionYoY(),
                command.stockCompToRevenue()
        );
        return scoringPolicy.evaluate(financials);
    }
}
