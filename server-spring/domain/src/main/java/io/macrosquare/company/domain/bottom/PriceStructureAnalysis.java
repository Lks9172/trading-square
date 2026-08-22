package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral interpretation of price structure.
 *
 * <p>The values describe market psychology visible in price, volume, support
 * zones, and swing structure. The score is a confluence score, never a return
 * probability or a stand-alone buy signal.</p>
 */
public record PriceStructureAnalysis(
        int score,
        TrendState trendState,
        BearishReversalStage bearishReversalStage,
        RecoveryStage recoveryStage,
        PriceLocation priceLocation,
        MovingAverageState movingAverageState,
        Double rsi14,
        Double sma20,
        Double sma50,
        Double sma100,
        Double sma200,
        Double movingAverageConvergencePct,
        Double channelLower,
        Double channelMid,
        Double channelUpper,
        Double channelPositionPct,
        Double channelAnnualizedSlopePct,
        PriceZone supportZone,
        PriceZone resistanceZone,
        int consolidationDays,
        Double consolidationRangePct,
        boolean volumeBreakout,
        boolean stopHuntReclaim,
        boolean oversoldConfluence,
        FibonacciRetracementAnalysis fibonacci,
        List<String> reasons,
        List<String> cautions,
        String methodology,
        List<PriceStructurePoint> points
) {
    public PriceStructureAnalysis {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        trendState = Objects.requireNonNull(trendState, "trendState");
        bearishReversalStage = Objects.requireNonNull(bearishReversalStage, "bearishReversalStage");
        recoveryStage = Objects.requireNonNull(recoveryStage, "recoveryStage");
        priceLocation = Objects.requireNonNull(priceLocation, "priceLocation");
        movingAverageState = Objects.requireNonNull(movingAverageState, "movingAverageState");
        requireFinite(
                rsi14, sma20, sma50, sma100, sma200, movingAverageConvergencePct,
                channelLower, channelMid, channelUpper, channelPositionPct,
                channelAnnualizedSlopePct, consolidationRangePct
        );
        if (consolidationDays < 0) throw new IllegalArgumentException("consolidationDays must not be negative");
        fibonacci = Objects.requireNonNull(fibonacci, "fibonacci");
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
        if (methodology == null || methodology.isBlank()) {
            throw new IllegalArgumentException("methodology is required");
        }
        points = List.copyOf(points == null ? List.of() : points);
    }

    public static PriceStructureAnalysis unavailable(List<PriceStructurePoint> points) {
        return new PriceStructureAnalysis(
                0,
                TrendState.UNAVAILABLE,
                BearishReversalStage.UNAVAILABLE,
                RecoveryStage.UNAVAILABLE,
                PriceLocation.UNAVAILABLE,
                MovingAverageState.UNAVAILABLE,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, 0, null,
                false, false, false,
                FibonacciRetracementAnalysis.unavailable(),
                List.of(),
                List.of("가격 구조를 계산하려면 유효한 일봉이 최소 60개 필요합니다."),
                PriceStructurePolicy.METHODOLOGY,
                points
        );
    }

    public record PriceZone(
            double lower,
            double upper,
            int touches,
            int strength,
            boolean roleFlip
    ) {
        public PriceZone {
            if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower <= 0 || upper < lower) {
                throw new IllegalArgumentException("invalid price zone");
            }
            if (touches < 1) throw new IllegalArgumentException("touches must be positive");
            if (strength < 0 || strength > 100) {
                throw new IllegalArgumentException("strength must be between 0 and 100");
            }
        }

        public double midpoint() {
            return (lower + upper) / 2.0;
        }

        public boolean contains(double value) {
            return value >= lower && value <= upper;
        }
    }

    public record PriceStructurePoint(
            LocalDate date,
            double close,
            Double sma20,
            Double sma50,
            Double sma100,
            Double sma200,
            Double channelLower,
            Double channelMid,
            Double channelUpper
    ) {
        public PriceStructurePoint {
            Objects.requireNonNull(date, "date");
            if (!Double.isFinite(close) || close <= 0) {
                throw new IllegalArgumentException("close must be positive and finite");
            }
            requireFinite(sma20, sma50, sma100, sma200, channelLower, channelMid, channelUpper);
        }
    }

    public enum TrendState {
        UPTREND,
        RANGE,
        DOWNTREND,
        TRANSITION,
        UNAVAILABLE
    }

    public enum BearishReversalStage {
        INTACT,
        MOMENTUM_WEAKENING,
        STRUCTURAL_CRACK,
        PRIOR_LOW_BROKEN,
        UNAVAILABLE
    }

    public enum RecoveryStage {
        NONE,
        BASE_BUILDING,
        REBOUND,
        STRUCTURE_BREAK,
        RETEST_HELD,
        UNAVAILABLE
    }

    public enum PriceLocation {
        BREAKOUT,
        LOWER_CHANNEL,
        SUPPORT_ZONE,
        MID_CHANNEL,
        RESISTANCE_ZONE,
        UPPER_CHANNEL,
        BREAKDOWN,
        UNAVAILABLE
    }

    public enum MovingAverageState {
        BULLISH_ALIGNED,
        CONVERGED,
        TRANSITION,
        BEARISH_ALIGNED,
        UNAVAILABLE
    }

    private static void requireFinite(Double... values) {
        for (var value : values) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("price structure number must be finite");
            }
        }
    }
}
