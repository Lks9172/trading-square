package io.macrosquare.research.domain.bottleneck;

public record BottleneckMetrics(
        Double revenueGrowthYoY,
        Double operatingMargin,
        Double evToSales,
        Integer totalScore
) {
}
