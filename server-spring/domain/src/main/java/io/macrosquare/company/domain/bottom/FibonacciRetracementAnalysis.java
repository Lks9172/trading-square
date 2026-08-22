package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral Fibonacci interpretation of the latest clear major swing.
 *
 * <p>The levels are potential reaction zones, not predicted returns or automatic
 * buy prices. Reliability rises only when a wider time frame and an independently
 * calculated support/resistance zone point to a similar price.</p>
 */
public record FibonacciRetracementAnalysis(
        SwingDirection swingDirection,
        LocalDate swingStartDate,
        LocalDate swingEndDate,
        Double swingStartPrice,
        Double swingEndPrice,
        Double swingAmplitudePct,
        Double currentPrice,
        Double currentRetracementRatio,
        List<FibonacciLevel> levels,
        Double nearestRatio,
        Double nearestPrice,
        Double nearestGapPct,
        TimeframeReliability timeframeReliability,
        boolean weeklyConfluence,
        boolean supportResistanceConfluence,
        boolean channelConfluence,
        int confluenceScore,
        ZoneState zoneState,
        String summary,
        List<String> cautions,
        String methodology
) {
    public FibonacciRetracementAnalysis {
        swingDirection = Objects.requireNonNull(swingDirection, "swingDirection");
        timeframeReliability = Objects.requireNonNull(timeframeReliability, "timeframeReliability");
        zoneState = Objects.requireNonNull(zoneState, "zoneState");
        requireFinite(
                swingStartPrice, swingEndPrice, swingAmplitudePct, currentPrice,
                currentRetracementRatio, nearestRatio, nearestPrice, nearestGapPct
        );
        if (confluenceScore < 0 || confluenceScore > 100) {
            throw new IllegalArgumentException("confluenceScore must be between 0 and 100");
        }
        levels = List.copyOf(levels == null ? List.of() : levels);
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
        summary = summary == null || summary.isBlank() ? "주요 파동을 식별하지 못했습니다." : summary.trim();
        if (methodology == null || methodology.isBlank()) {
            throw new IllegalArgumentException("methodology is required");
        }
        if (swingDirection == SwingDirection.UNAVAILABLE) {
            if (swingStartDate != null || swingEndDate != null || !levels.isEmpty()) {
                throw new IllegalArgumentException("unavailable Fibonacci analysis must not expose a swing");
            }
        } else {
            Objects.requireNonNull(swingStartDate, "swingStartDate");
            Objects.requireNonNull(swingEndDate, "swingEndDate");
            if (swingEndDate.isBefore(swingStartDate)) {
                throw new IllegalArgumentException("swingEndDate must not precede swingStartDate");
            }
            requirePositive(swingStartPrice, "swingStartPrice");
            requirePositive(swingEndPrice, "swingEndPrice");
            requirePositive(currentPrice, "currentPrice");
            if (levels.size() != 5) {
                throw new IllegalArgumentException("five standard Fibonacci levels are required");
            }
        }
    }

    public static FibonacciRetracementAnalysis unavailable() {
        return new FibonacciRetracementAnalysis(
                SwingDirection.UNAVAILABLE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                TimeframeReliability.UNAVAILABLE,
                false,
                false,
                false,
                0,
                ZoneState.UNAVAILABLE,
                "최근의 명확한 주요 파동을 식별하지 못했습니다.",
                List.of("피보나치는 작은 흔들림이 아니라 명확한 고점·저점 파동에서만 계산합니다."),
                PriceStructurePolicy.FIBONACCI_METHODOLOGY
        );
    }

    public record FibonacciLevel(double ratio, double price, String label) {
        public FibonacciLevel {
            if (!Double.isFinite(ratio) || ratio <= 0 || ratio >= 1) {
                throw new IllegalArgumentException("ratio must be between 0 and 1");
            }
            if (!Double.isFinite(price) || price <= 0) {
                throw new IllegalArgumentException("price must be positive and finite");
            }
            label = label == null || label.isBlank() ? Double.toString(ratio) : label.trim();
        }
    }

    public enum SwingDirection {
        UP_SWING,
        DOWN_SWING,
        UNAVAILABLE
    }

    public enum TimeframeReliability {
        WEEKLY_CONFIRMED,
        DAILY_ONLY,
        UNAVAILABLE
    }

    public enum ZoneState {
        EXTENSION,
        SHALLOW_RETRACEMENT,
        MODERATE_RETRACEMENT,
        DEEP_RETRACEMENT,
        LAST_DEFENSE,
        LAST_DEFENSE_BROKEN,
        UNAVAILABLE
    }

    private static void requirePositive(Double value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireFinite(Double... values) {
        for (var value : values) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("Fibonacci number must be finite");
            }
        }
    }
}
