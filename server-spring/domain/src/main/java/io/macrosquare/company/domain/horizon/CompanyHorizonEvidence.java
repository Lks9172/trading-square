package io.macrosquare.company.domain.horizon;

public record CompanyHorizonEvidence(
        Integer companyScore,
        Integer qualityScore,
        Integer growthScore,
        Integer valuationScore,
        Integer balanceSheetScore,
        Integer buyScore,
        Integer bottomScore,
        Integer reversalScore,
        Integer technicalScore
) {
    public CompanyHorizonEvidence {
        score(companyScore, "companyScore");
        score(qualityScore, "qualityScore");
        score(growthScore, "growthScore");
        score(valuationScore, "valuationScore");
        score(balanceSheetScore, "balanceSheetScore");
        score(buyScore, "buyScore");
        score(bottomScore, "bottomScore");
        score(reversalScore, "reversalScore");
        score(technicalScore, "technicalScore");
    }

    private static void score(Integer value, String field) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }
}
