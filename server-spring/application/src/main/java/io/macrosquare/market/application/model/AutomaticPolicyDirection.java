package io.macrosquare.market.application.model;

import java.time.Instant;

/** Market-context view of an external policy assessment. */
public record AutomaticPolicyDirection(
        int direction,
        int confidence,
        String source,
        Instant asOf
) {
    public AutomaticPolicyDirection {
        if (direction < -2 || direction > 2) throw new IllegalArgumentException("direction must be between -2 and 2");
        if (confidence < 0 || confidence > 100) throw new IllegalArgumentException("confidence must be between 0 and 100");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (asOf == null) throw new IllegalArgumentException("asOf is required");
    }
}
