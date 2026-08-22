package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;

public record DeepBottomEvidence(
        LocalDate signalDate,
        Double absorptionVolumeVsRecent2dRatio,
        Double absorptionVolumeVsRecent3dRatio,
        Double contractionRatio,
        Double drawdown120dPct,
        Double ma20GapPct,
        Double recentDrop3dPct,
        boolean ma20Below50,
        Integer daysSinceAbsorption,
        Double reboundSinceAbsorptionPct,
        Integer failureRiskScore
) {
    public DeepBottomEvidence {
        requireNonNegativeFinite(absorptionVolumeVsRecent2dRatio, "absorptionVolumeVsRecent2dRatio");
        requireNonNegativeFinite(absorptionVolumeVsRecent3dRatio, "absorptionVolumeVsRecent3dRatio");
        requireNonNegativeFinite(contractionRatio, "contractionRatio");
        requireFinite(drawdown120dPct, "drawdown120dPct");
        requireFinite(ma20GapPct, "ma20GapPct");
        requireFinite(recentDrop3dPct, "recentDrop3dPct");
        requireFinite(reboundSinceAbsorptionPct, "reboundSinceAbsorptionPct");
        if (daysSinceAbsorption != null && daysSinceAbsorption < 0) {
            throw new IllegalArgumentException("daysSinceAbsorption must be non-negative");
        }
        if (failureRiskScore != null && (failureRiskScore < 0 || failureRiskScore > 100)) {
            throw new IllegalArgumentException("failureRiskScore must be between 0 and 100");
        }
    }

    public Double recentVolumeRatio() {
        // Both comparisons describe the same absorption candle.  The 3-day
        // denominator includes one more session and is therefore the stricter
        // proof that the candle beat every one of the preceding three days.
        // Never select the larger ratio: doing so silently weakens the rule to
        // "beat the previous two days" when the third day had larger volume.
        if (absorptionVolumeVsRecent2dRatio == null || absorptionVolumeVsRecent3dRatio == null) {
            return null;
        }
        return Math.min(absorptionVolumeVsRecent2dRatio, absorptionVolumeVsRecent3dRatio);
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static void requireNonNegativeFinite(Double value, String field) {
        requireFinite(value, field);
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
