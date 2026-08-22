package io.macrosquare.company.application.port.in;

public record ScoreCompanyCommand(
        String ticker,
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
