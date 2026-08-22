package io.macrosquare.company.domain.model;

import java.util.Objects;

/**
 * Normalized core company fundamentals owned by the Spring domain policy.
 */
public record CompanyFundamentalsSnapshot(
        Ticker ticker,
        String cik,
        String asOf,
        Double revenueTtm,
        Double operatingIncomeTtm,
        Double netIncomeTtm,
        Double freeCashFlowTtm,
        Double cash,
        Double debt,
        Double currentAssets,
        Double currentLiabilities,
        Double receivables,
        Double inventory,
        Double capexTtm,
        Double operatingCashFlowTtm,
        Double sharesOutstanding,
        Double marketCap,
        Double enterpriseValue,
        Double revenueGrowthYoY,
        Double operatingMargin,
        Double operatingMarginTrend,
        Double freeCashFlowMargin,
        Double netDebtToRevenue,
        Double evToSales,
        Double evToFcf,
        Double shareDilutionYoY,
        Double stockCompToRevenue,
        Double roe,
        Double currentRatio,
        Double receivablesToRevenue,
        Double inventoryToRevenue,
        Double roic,
        Double effectiveTaxRate,
        boolean roicEstimated,
        Double shareDilution3yCagr,
        Double accrualRatio,
        CompanyValuationQuality valuationQuality
) {
    public CompanyFundamentalsSnapshot {
        Objects.requireNonNull(ticker, "ticker must not be null");
        if (cik == null || cik.isBlank()) throw new IllegalArgumentException("cik must not be blank");
        if (asOf == null || asOf.isBlank()) throw new IllegalArgumentException("asOf must not be blank");
        valuationQuality = Objects.requireNonNull(valuationQuality, "valuationQuality must not be null");
    }

    /** Compatibility constructor for callers created before valuation provenance was explicit. */
    public CompanyFundamentalsSnapshot(
            Ticker ticker,
            String cik,
            String asOf,
            Double revenueTtm,
            Double operatingIncomeTtm,
            Double netIncomeTtm,
            Double freeCashFlowTtm,
            Double cash,
            Double debt,
            Double currentAssets,
            Double currentLiabilities,
            Double receivables,
            Double inventory,
            Double capexTtm,
            Double operatingCashFlowTtm,
            Double sharesOutstanding,
            Double marketCap,
            Double enterpriseValue,
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double operatingMarginTrend,
            Double freeCashFlowMargin,
            Double netDebtToRevenue,
            Double evToSales,
            Double evToFcf,
            Double shareDilutionYoY,
            Double stockCompToRevenue,
            Double roe,
            Double currentRatio,
            Double receivablesToRevenue,
            Double inventoryToRevenue,
            Double roic,
            Double effectiveTaxRate,
            boolean roicEstimated,
            Double shareDilution3yCagr,
            Double accrualRatio
    ) {
        this(
                ticker, cik, asOf, revenueTtm, operatingIncomeTtm, netIncomeTtm, freeCashFlowTtm,
                cash, debt, currentAssets, currentLiabilities, receivables, inventory, capexTtm,
                operatingCashFlowTtm, sharesOutstanding, marketCap, enterpriseValue, revenueGrowthYoY,
                operatingMargin, operatingMarginTrend, freeCashFlowMargin, netDebtToRevenue, evToSales,
                evToFcf, shareDilutionYoY, stockCompToRevenue, roe, currentRatio,
                receivablesToRevenue, inventoryToRevenue, roic, effectiveTaxRate, roicEstimated,
                shareDilution3yCagr, accrualRatio,
                CompanyValuationQuality.unavailable("valuation provenance unavailable in legacy projection")
        );
    }

    /** Compatibility constructor for persisted projections created before the quality extension. */
    public CompanyFundamentalsSnapshot(
            Ticker ticker,
            String cik,
            String asOf,
            Double revenueTtm,
            Double operatingIncomeTtm,
            Double netIncomeTtm,
            Double freeCashFlowTtm,
            Double cash,
            Double debt,
            Double currentAssets,
            Double currentLiabilities,
            Double receivables,
            Double inventory,
            Double capexTtm,
            Double operatingCashFlowTtm,
            Double sharesOutstanding,
            Double marketCap,
            Double enterpriseValue,
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double operatingMarginTrend,
            Double freeCashFlowMargin,
            Double netDebtToRevenue,
            Double evToSales,
            Double evToFcf,
            Double shareDilutionYoY,
            Double stockCompToRevenue,
            Double roe,
            Double currentRatio,
            Double receivablesToRevenue,
            Double inventoryToRevenue
    ) {
        this(
                ticker, cik, asOf, revenueTtm, operatingIncomeTtm, netIncomeTtm, freeCashFlowTtm,
                cash, debt, currentAssets, currentLiabilities, receivables, inventory, capexTtm,
                operatingCashFlowTtm, sharesOutstanding, marketCap, enterpriseValue, revenueGrowthYoY,
                operatingMargin, operatingMarginTrend, freeCashFlowMargin, netDebtToRevenue, evToSales,
                evToFcf, shareDilutionYoY, stockCompToRevenue, roe, currentRatio,
                receivablesToRevenue, inventoryToRevenue, null, null, false, null, null,
                CompanyValuationQuality.unavailable("valuation provenance unavailable in legacy projection")
        );
    }

    public CompanyFinancials scoringFinancials() {
        // Defense in depth: an ineligible valuation must never leak into a
        // score even if a caller constructs or hydrates a snapshot containing
        // diagnostic EV multiples.
        var scoreEvToSales = valuationQuality.valuationEligible() ? evToSales : null;
        var scoreEvToFcf = valuationQuality.valuationEligible() ? evToFcf : null;
        return new CompanyFinancials(
                ticker,
                revenueGrowthYoY,
                operatingMargin,
                freeCashFlowMargin,
                roe,
                operatingMarginTrend,
                scoreEvToSales,
                scoreEvToFcf,
                netDebtToRevenue,
                cash,
                debt,
                shareDilutionYoY,
                stockCompToRevenue,
                roic,
                shareDilution3yCagr,
                accrualRatio
        );
    }
}
