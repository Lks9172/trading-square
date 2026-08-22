package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Transport-free price and volume evidence used by bottom policies.
 *
 * <p>The values intentionally preserve the validated calculation precision so
 * historical signal comparisons remain reproducible.</p>
 */
public record BottomPriceContext(
        Double drawdownFromHighPct,
        Double drawdownFrom120dHighPct,
        Double reboundFromLowPct,
        Double return30dPct,
        Double volumeTrend20dPct,
        Double ma20GapPct,
        boolean ma20Below50,
        Double recentDrop3dPct,
        List<BottomPatternPoint> chartPoints,
        BottomPatternAnalysis pattern,
        Double candidateVolumeRatio,
        Double confirmVolumeRatio,
        Double retestVolumeRatio,
        Double absorptionVolumeVsRecent2dRatio,
        Double absorptionVolumeVsRecent3dRatio,
        Double absorptionDropPct,
        Double priorDeclineDropPct,
        Double absorptionContractionRatio,
        LocalDate absorptionDate,
        Integer daysSinceAbsorption,
        Double reboundSinceAbsorptionPct
) {
    public BottomPriceContext {
        chartPoints = List.copyOf(Objects.requireNonNull(chartPoints, "chartPoints"));
        pattern = Objects.requireNonNull(pattern, "pattern");
        requireFinite(drawdownFromHighPct, "drawdownFromHighPct");
        requireFinite(drawdownFrom120dHighPct, "drawdownFrom120dHighPct");
        requireFinite(reboundFromLowPct, "reboundFromLowPct");
        requireFinite(return30dPct, "return30dPct");
        requireFinite(volumeTrend20dPct, "volumeTrend20dPct");
        requireFinite(ma20GapPct, "ma20GapPct");
        requireFinite(recentDrop3dPct, "recentDrop3dPct");
        requireNonNegative(candidateVolumeRatio, "candidateVolumeRatio");
        requireNonNegative(confirmVolumeRatio, "confirmVolumeRatio");
        requireNonNegative(retestVolumeRatio, "retestVolumeRatio");
        requireNonNegative(absorptionVolumeVsRecent2dRatio, "absorptionVolumeVsRecent2dRatio");
        requireNonNegative(absorptionVolumeVsRecent3dRatio, "absorptionVolumeVsRecent3dRatio");
        requireFinite(absorptionDropPct, "absorptionDropPct");
        requireFinite(priorDeclineDropPct, "priorDeclineDropPct");
        requireNonNegative(absorptionContractionRatio, "absorptionContractionRatio");
        requireFinite(reboundSinceAbsorptionPct, "reboundSinceAbsorptionPct");
        if (daysSinceAbsorption != null && daysSinceAbsorption < 0) {
            throw new IllegalArgumentException("daysSinceAbsorption must be non-negative");
        }
    }

    public DeepBottomEvidence toDeepBottomEvidence(int failureRiskScore) {
        return new DeepBottomEvidence(
                absorptionDate,
                absorptionVolumeVsRecent2dRatio,
                absorptionVolumeVsRecent3dRatio,
                absorptionContractionRatio,
                drawdownFrom120dHighPct,
                ma20GapPct,
                recentDrop3dPct,
                ma20Below50,
                daysSinceAbsorption,
                reboundSinceAbsorptionPct,
                failureRiskScore
        );
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static void requireNonNegative(Double value, String field) {
        requireFinite(value, field);
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
