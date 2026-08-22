package io.macrosquare.company.domain.model;

import java.util.Objects;

public record CompanyFinancials(
        Ticker ticker,
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
        Double stockCompToRevenue,
        Double roic,
        Double shareDilution3yCagr,
        Double accrualRatio
) {
    public CompanyFinancials {
        Objects.requireNonNull(ticker, "ticker must not be null");
    }

    public CompanyFinancials(
            Ticker ticker,
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
        this(
                ticker, revenueGrowthYoY, operatingMargin, freeCashFlowMargin, roe,
                operatingMarginTrend, evToSales, evToFcf, netDebtToRevenue, cash, debt,
                shareDilutionYoY, stockCompToRevenue, null, null, null
        );
    }
}
