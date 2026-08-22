package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record SectorWalkForwardBacktest(
        String methodologyVersion,
        LocalDate from,
        LocalDate to,
        int rebalanceCount,
        Map<String, HorizonResult> horizons,
        Map<String, HorizonResult> comparisonBaselineHorizons,
        double averageMonthlyTurnoverPct,
        List<SignalEvent> events
) {
    public SectorWalkForwardBacktest {
        if (methodologyVersion == null || methodologyVersion.isBlank()) {
            throw new IllegalArgumentException("methodologyVersion is required");
        }
        if (from == null || to == null || to.isBefore(from) || rebalanceCount < 0) {
            throw new IllegalArgumentException("backtest range is invalid");
        }
        horizons = Map.copyOf(horizons == null ? Map.of() : horizons);
        comparisonBaselineHorizons = Map.copyOf(
                comparisonBaselineHorizons == null ? Map.of() : comparisonBaselineHorizons);
        if (!Double.isFinite(averageMonthlyTurnoverPct)
                || averageMonthlyTurnoverPct < 0 || averageMonthlyTurnoverPct > 100) {
            throw new IllegalArgumentException("averageMonthlyTurnoverPct must be between 0 and 100");
        }
        events = List.copyOf(events == null ? List.of() : events);
    }

    public record HorizonResult(
            int months,
            int sampleCount,
            double top1HitRatePct,
            double top3HitRatePct,
            double top1AverageExcessPct,
            double top3AverageExcessPct,
            double top1MedianExcessPct,
            double top3MedianExcessPct,
            double top1PositiveReturnRatePct,
            double top3PositiveReturnRatePct,
            double top1UniverseHitRatePct,
            double top3UniverseHitRatePct,
            double top1AverageUniverseExcessPct,
            double top3AverageUniverseExcessPct,
            double top1HitRate95LowerPct,
            double top1HitRate95UpperPct,
            int overlapAdjustmentLagMonths,
            double top1HitRateOverlapAdjusted95LowerPct,
            double top1HitRateOverlapAdjusted95UpperPct
    ) {
        public HorizonResult {
            if (months < 1 || sampleCount < 0) throw new IllegalArgumentException("horizon is invalid");
            for (var value : new double[]{
                    top1HitRatePct, top3HitRatePct, top1AverageExcessPct,
                    top3AverageExcessPct, top1MedianExcessPct, top3MedianExcessPct,
                    top1PositiveReturnRatePct, top3PositiveReturnRatePct,
                    top1UniverseHitRatePct, top3UniverseHitRatePct,
                    top1AverageUniverseExcessPct, top3AverageUniverseExcessPct,
                    top1HitRate95LowerPct, top1HitRate95UpperPct,
                    top1HitRateOverlapAdjusted95LowerPct,
                    top1HitRateOverlapAdjusted95UpperPct}) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("horizon metric must be finite");
            }
            if (overlapAdjustmentLagMonths < 0) {
                throw new IllegalArgumentException("overlapAdjustmentLagMonths must not be negative");
            }
        }
    }

    public record SignalEvent(
            LocalDate signalDate,
            String top1,
            List<String> top3,
            Map<String, ForwardResult> forward
    ) {
        public SignalEvent {
            if (signalDate == null || top1 == null || top1.isBlank()) {
                throw new IllegalArgumentException("signal event is invalid");
            }
            top3 = List.copyOf(top3 == null ? List.of() : top3);
            forward = Map.copyOf(forward == null ? Map.of() : forward);
        }
    }

    public record ForwardResult(
            double benchmarkReturnPct,
            double universeReturnPct,
            double top1ReturnPct,
            double top3ReturnPct,
            double top1ExcessPct,
            double top3ExcessPct,
            double top1UniverseExcessPct,
            double top3UniverseExcessPct
    ) {
        public ForwardResult {
            for (var value : new double[]{
                    benchmarkReturnPct, universeReturnPct, top1ReturnPct, top3ReturnPct,
                    top1ExcessPct, top3ExcessPct, top1UniverseExcessPct, top3UniverseExcessPct}) {
                if (!Double.isFinite(value)) throw new IllegalArgumentException("forward result must be finite");
            }
        }
    }
}
