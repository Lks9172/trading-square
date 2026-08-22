package io.macrosquare.company.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

public record CompanyScoreRequest(
        @NotBlank String ticker,
        Double revenueGrowthYoY,
        Double operatingMargin,
        Double freeCashFlowMargin,
        Double roe,
        Double operatingMarginTrend,
        Double evToSales,
        Double evToFcf,
        Double netDebtToRevenue,
        Double cash,
        Double debt,
        Double shareDilutionYoY,
        Double stockCompToRevenue
) {
}
