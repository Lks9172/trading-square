package io.macrosquare.company.domain.model;

import java.util.List;

/** Current-fundamentals view of cash conversion and accounting quality. */
public record CompanyFinancialQualityAssessment(
        int cashConversionScore,
        int earningsQualityScore,
        Risk accrualRisk,
        Double operatingCashFlowToNetIncome,
        String liquidityLabel,
        String summary,
        List<String> reasons
) {
    public CompanyFinancialQualityAssessment {
        validate(cashConversionScore, "cashConversionScore");
        validate(earningsQualityScore, "earningsQualityScore");
        if (accrualRisk == null) throw new IllegalArgumentException("accrualRisk is required");
        if (operatingCashFlowToNetIncome != null && !Double.isFinite(operatingCashFlowToNetIncome)) {
            throw new IllegalArgumentException("operatingCashFlowToNetIncome must be finite");
        }
        if (liquidityLabel == null || liquidityLabel.isBlank()) {
            throw new IllegalArgumentException("liquidityLabel is required");
        }
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    public enum Risk { LOW, MODERATE, HIGH, UNAVAILABLE }

    private static void validate(int value, String field) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be between 0 and 100");
    }
}
