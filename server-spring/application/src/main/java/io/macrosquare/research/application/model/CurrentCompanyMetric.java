package io.macrosquare.research.application.model;

import java.time.Instant;

/** Current company metric projection consumed by the research catalog context. */
public record CurrentCompanyMetric(
        String ticker,
        Double marketCap,
        Integer totalScore,
        Integer qualityScore,
        Integer buyScore,
        String buyLabel,
        Integer appealScore,
        Integer crowdingScore,
        Double revenueGrowthYoY,
        Double operatingMargin,
        Double evToSales,
        Integer priceBottomScore,
        Integer volumeConfirmationScore,
        Integer failureRiskScore,
        Integer confirmedBottomScore,
        String confirmedBottomState,
        boolean valuationEligible,
        Instant updatedAt
) {
}
