package io.macrosquare.company.domain.horizon;

public record HorizonWalkForwardMetric(
        CompanyHorizon horizon,
        int forwardTradingDays,
        double targetReturnPct,
        int signalThreshold,
        int signalCount,
        Double positiveHitRatePct,
        Double targetHitRatePct,
        Double averageReturnPct,
        Double medianReturnPct,
        Double averageDaysToTarget,
        Double averageMaxDrawdownPct
) {
    public HorizonWalkForwardMetric {
        if (forwardTradingDays <= 0) throw new IllegalArgumentException("forwardTradingDays must be positive");
        if (signalThreshold < 0 || signalThreshold > 100) {
            throw new IllegalArgumentException("signalThreshold must be between 0 and 100");
        }
        if (signalCount < 0) throw new IllegalArgumentException("signalCount must be non-negative");
    }
}
