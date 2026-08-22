package io.macrosquare.notification.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Compact notification-owned MACD evidence.
 *
 * <p>This is descriptive timing context, never an eligibility score or an
 * execution action. The anti-corruption adapter maps company evidence into
 * this type so the notification domain does not depend on company models.</p>
 */
public record TechnicalTimingEvidence(
        Timeframe daily,
        Timeframe weekly,
        boolean currentWeekProvisional
) {
    public TechnicalTimingEvidence {
        Objects.requireNonNull(daily, "daily");
        Objects.requireNonNull(weekly, "weekly");
    }

    public record Timeframe(
            LocalDate asOf,
            Position position,
            Cross latestCross,
            LocalDate crossDate,
            Integer periodsSinceCross,
            Histogram histogram,
            Divergence divergence,
            LocalDate divergenceConfirmedDate,
            Integer periodsSinceDivergence,
            boolean divergenceActive
    ) {
        public Timeframe {
            if (position == null) position = Position.UNAVAILABLE;
            if (latestCross == null) latestCross = Cross.UNAVAILABLE;
            if (histogram == null) histogram = Histogram.UNAVAILABLE;
            if (divergence == null) divergence = Divergence.UNAVAILABLE;
            if (periodsSinceCross != null && periodsSinceCross < 0) {
                throw new IllegalArgumentException("periodsSinceCross must not be negative");
            }
            if (periodsSinceDivergence != null && periodsSinceDivergence < 0) {
                throw new IllegalArgumentException("periodsSinceDivergence must not be negative");
            }
        }
    }

    public enum Position { ABOVE_SIGNAL, BELOW_SIGNAL, AT_SIGNAL, UNAVAILABLE }

    public enum Cross { BULLISH_CROSS, BEARISH_CROSS, NONE, UNAVAILABLE }

    public enum Histogram {
        EXPANDING_POSITIVE,
        CONTRACTING_POSITIVE,
        EXPANDING_NEGATIVE,
        CONTRACTING_NEGATIVE,
        FLAT,
        UNAVAILABLE
    }

    public enum Divergence { BULLISH, BEARISH, NONE, UNAVAILABLE }
}
