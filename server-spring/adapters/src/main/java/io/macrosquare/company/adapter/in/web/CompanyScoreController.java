package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.ScoreCompanyCommand;
import io.macrosquare.company.application.port.in.ScoreCompanyUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/company-score")
public final class CompanyScoreController {

    private final ScoreCompanyUseCase scoreCompanyUseCase;

    public CompanyScoreController(ScoreCompanyUseCase scoreCompanyUseCase) {
        this.scoreCompanyUseCase = Objects.requireNonNull(scoreCompanyUseCase);
    }

    @PostMapping("/evaluate")
    public CompanyScoreResponse evaluate(@Valid @RequestBody CompanyScoreRequest request) {
        var command = new ScoreCompanyCommand(
                request.ticker(),
                request.revenueGrowthYoY(),
                request.operatingMargin(),
                request.freeCashFlowMargin(),
                request.roe(),
                request.operatingMarginTrend(),
                request.evToSales(),
                request.evToFcf(),
                request.netDebtToRevenue(),
                request.cash(),
                request.debt(),
                request.shareDilutionYoY(),
                request.stockCompToRevenue()
        );
        return CompanyScoreResponse.from(scoreCompanyUseCase.score(command));
    }
}
