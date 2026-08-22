package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * Semantic fact series required to normalize company fundamentals.
 *
 * <p>This model intentionally contains no SEC taxonomy keys, JSON nodes, HTTP
 * metadata, cache entries, or persistence records.</p>
 */
public record CompanyFundamentalsEvidence(
        List<FinancialFactPoint> revenue,
        List<FinancialFactPoint> operatingIncome,
        List<FinancialFactPoint> netIncome,
        List<FinancialFactPoint> operatingCashFlow,
        List<FinancialFactPoint> capitalExpenditure,
        List<FinancialFactPoint> cash,
        List<FinancialFactPoint> debt,
        List<FinancialFactPoint> sharesOutstanding,
        List<FinancialFactPoint> stockCompensation,
        List<FinancialFactPoint> stockholdersEquity,
        List<FinancialFactPoint> currentAssets,
        List<FinancialFactPoint> currentLiabilities,
        List<FinancialFactPoint> receivables,
        List<FinancialFactPoint> inventory,
        List<FinancialFactPoint> pretaxIncome,
        List<FinancialFactPoint> incomeTaxExpense,
        List<FinancialFactPoint> totalAssets,
        List<FinancialFactPoint> weightedAverageDilutedShares,
        List<FinancialFactPoint> costsAndExpenses
) {
    public CompanyFundamentalsEvidence {
        revenue = immutable(revenue, "revenue");
        operatingIncome = immutable(operatingIncome, "operatingIncome");
        netIncome = immutable(netIncome, "netIncome");
        operatingCashFlow = immutable(operatingCashFlow, "operatingCashFlow");
        capitalExpenditure = immutable(capitalExpenditure, "capitalExpenditure");
        cash = immutable(cash, "cash");
        debt = immutable(debt, "debt");
        sharesOutstanding = immutable(sharesOutstanding, "sharesOutstanding");
        stockCompensation = immutable(stockCompensation, "stockCompensation");
        stockholdersEquity = immutable(stockholdersEquity, "stockholdersEquity");
        currentAssets = immutable(currentAssets, "currentAssets");
        currentLiabilities = immutable(currentLiabilities, "currentLiabilities");
        receivables = immutable(receivables, "receivables");
        inventory = immutable(inventory, "inventory");
        pretaxIncome = immutable(pretaxIncome, "pretaxIncome");
        incomeTaxExpense = immutable(incomeTaxExpense, "incomeTaxExpense");
        totalAssets = immutable(totalAssets, "totalAssets");
        weightedAverageDilutedShares = immutable(weightedAverageDilutedShares, "weightedAverageDilutedShares");
        costsAndExpenses = immutable(costsAndExpenses, "costsAndExpenses");
    }

    /** Compatibility constructor for callers before the revenue identity fallback. */
    public CompanyFundamentalsEvidence(
            List<FinancialFactPoint> revenue,
            List<FinancialFactPoint> operatingIncome,
            List<FinancialFactPoint> netIncome,
            List<FinancialFactPoint> operatingCashFlow,
            List<FinancialFactPoint> capitalExpenditure,
            List<FinancialFactPoint> cash,
            List<FinancialFactPoint> debt,
            List<FinancialFactPoint> sharesOutstanding,
            List<FinancialFactPoint> stockCompensation,
            List<FinancialFactPoint> stockholdersEquity,
            List<FinancialFactPoint> currentAssets,
            List<FinancialFactPoint> currentLiabilities,
            List<FinancialFactPoint> receivables,
            List<FinancialFactPoint> inventory,
            List<FinancialFactPoint> pretaxIncome,
            List<FinancialFactPoint> incomeTaxExpense,
            List<FinancialFactPoint> totalAssets,
            List<FinancialFactPoint> weightedAverageDilutedShares
    ) {
        this(
                revenue, operatingIncome, netIncome, operatingCashFlow, capitalExpenditure,
                cash, debt, sharesOutstanding, stockCompensation, stockholdersEquity,
                currentAssets, currentLiabilities, receivables, inventory,
                pretaxIncome, incomeTaxExpense, totalAssets, weightedAverageDilutedShares,
                List.of()
        );
    }

    /** Compatibility constructor for callers before diluted-share normalization. */
    public CompanyFundamentalsEvidence(
            List<FinancialFactPoint> revenue,
            List<FinancialFactPoint> operatingIncome,
            List<FinancialFactPoint> netIncome,
            List<FinancialFactPoint> operatingCashFlow,
            List<FinancialFactPoint> capitalExpenditure,
            List<FinancialFactPoint> cash,
            List<FinancialFactPoint> debt,
            List<FinancialFactPoint> sharesOutstanding,
            List<FinancialFactPoint> stockCompensation,
            List<FinancialFactPoint> stockholdersEquity,
            List<FinancialFactPoint> currentAssets,
            List<FinancialFactPoint> currentLiabilities,
            List<FinancialFactPoint> receivables,
            List<FinancialFactPoint> inventory,
            List<FinancialFactPoint> pretaxIncome,
            List<FinancialFactPoint> incomeTaxExpense,
            List<FinancialFactPoint> totalAssets
    ) {
        this(
                revenue, operatingIncome, netIncome, operatingCashFlow, capitalExpenditure,
                cash, debt, sharesOutstanding, stockCompensation, stockholdersEquity,
                currentAssets, currentLiabilities, receivables, inventory,
                pretaxIncome, incomeTaxExpense, totalAssets, List.of(), List.of()
        );
    }

    /**
     * Compatibility constructor for callers that only provide the original
     * normalization contract. New quality metrics remain unavailable rather
     * than being fabricated.
     */
    public CompanyFundamentalsEvidence(
            List<FinancialFactPoint> revenue,
            List<FinancialFactPoint> operatingIncome,
            List<FinancialFactPoint> netIncome,
            List<FinancialFactPoint> operatingCashFlow,
            List<FinancialFactPoint> capitalExpenditure,
            List<FinancialFactPoint> cash,
            List<FinancialFactPoint> debt,
            List<FinancialFactPoint> sharesOutstanding,
            List<FinancialFactPoint> stockCompensation,
            List<FinancialFactPoint> stockholdersEquity,
            List<FinancialFactPoint> currentAssets,
            List<FinancialFactPoint> currentLiabilities,
            List<FinancialFactPoint> receivables,
            List<FinancialFactPoint> inventory
    ) {
        this(
                revenue, operatingIncome, netIncome, operatingCashFlow, capitalExpenditure,
                cash, debt, sharesOutstanding, stockCompensation, stockholdersEquity,
                currentAssets, currentLiabilities, receivables, inventory,
                List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    public boolean hasCoreFinancialEvidence() {
        return !revenue.isEmpty()
                || !operatingIncome.isEmpty()
                || !netIncome.isEmpty()
                || !operatingCashFlow.isEmpty();
    }

    private static List<FinancialFactPoint> immutable(List<FinancialFactPoint> source, String field) {
        return List.copyOf(Objects.requireNonNull(source, field + " must not be null"));
    }
}
