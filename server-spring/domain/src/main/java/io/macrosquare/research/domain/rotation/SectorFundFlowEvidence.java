package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;

/** Creation/redemption activity inferred directly from official fund shares outstanding. */
public record SectorFundFlowEvidence(
        LocalDate observedOn,
        double nav,
        double sharesOutstanding,
        double totalNetAssets,
        double flow1dUsd,
        double flow5dUsd,
        double flow20dUsd,
        double flow5dPct,
        double flow20dPct,
        int score
) {
    public SectorFundFlowEvidence {
        Objects.requireNonNull(observedOn, "observedOn");
        requirePositiveFinite(nav, "nav");
        requirePositiveFinite(sharesOutstanding, "sharesOutstanding");
        requirePositiveFinite(totalNetAssets, "totalNetAssets");
        requireFinite(flow1dUsd, "flow1dUsd");
        requireFinite(flow5dUsd, "flow5dUsd");
        requireFinite(flow20dUsd, "flow20dUsd");
        requireFinite(flow5dPct, "flow5dPct");
        requireFinite(flow20dPct, "flow20dPct");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
    }

    private static void requirePositiveFinite(double value, String field) {
        requireFinite(value, field);
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
