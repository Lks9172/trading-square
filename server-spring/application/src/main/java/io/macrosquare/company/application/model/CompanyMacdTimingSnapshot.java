package io.macrosquare.company.application.model;

import java.time.LocalDate;
import java.util.Set;

/**
 * Persistable, transport-neutral subset of the current company MACD analysis.
 *
 * <p>The company context owns this read model. Notification adapters translate
 * it into their own bounded-context evidence instead of importing company or
 * technical domain types into the notification domain.</p>
 */
public record CompanyMacdTimingSnapshot(
        Timeframe daily,
        Timeframe weekly,
        boolean currentWeekProvisional
) {
    public CompanyMacdTimingSnapshot {
        if (daily == null || weekly == null) {
            throw new IllegalArgumentException("daily and weekly MACD timing are required");
        }
    }

    public record Timeframe(
            LocalDate asOf,
            String position,
            String latestCross,
            LocalDate crossDate,
            Integer periodsSinceCross,
            String histogramState,
            String divergence,
            LocalDate divergenceConfirmedDate,
            Integer periodsSinceDivergence,
            boolean divergenceActive
    ) {
        private static final Set<String> POSITIONS = Set.of(
                "ABOVE_SIGNAL", "BELOW_SIGNAL", "AT_SIGNAL", "UNAVAILABLE");
        private static final Set<String> CROSSES = Set.of(
                "BULLISH_CROSS", "BEARISH_CROSS", "NONE", "UNAVAILABLE");
        private static final Set<String> HISTOGRAM_STATES = Set.of(
                "EXPANDING_POSITIVE", "CONTRACTING_POSITIVE", "EXPANDING_NEGATIVE",
                "CONTRACTING_NEGATIVE", "FLAT", "UNAVAILABLE");
        private static final Set<String> DIVERGENCES = Set.of(
                "BULLISH", "BEARISH", "NONE", "UNAVAILABLE");

        public Timeframe {
            position = normalize(position, POSITIONS, "UNAVAILABLE");
            latestCross = normalize(latestCross, CROSSES, "UNAVAILABLE");
            histogramState = normalize(histogramState, HISTOGRAM_STATES, "UNAVAILABLE");
            divergence = normalize(divergence, DIVERGENCES, "UNAVAILABLE");
            if (periodsSinceCross != null && periodsSinceCross < 0) {
                throw new IllegalArgumentException("periodsSinceCross must not be negative");
            }
            if (periodsSinceDivergence != null && periodsSinceDivergence < 0) {
                throw new IllegalArgumentException("periodsSinceDivergence must not be negative");
            }
        }

        private static String normalize(String value, Set<String> accepted, String fallback) {
            if (value == null || !accepted.contains(value)) return fallback;
            return value;
        }
    }
}
