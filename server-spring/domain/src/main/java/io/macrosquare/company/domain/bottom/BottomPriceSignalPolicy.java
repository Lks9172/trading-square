package io.macrosquare.company.domain.bottom;

import java.util.Objects;

/** Pure price/volume bottom signal. Fundamental and crowding gates belong to the investment decision. */
public final class BottomPriceSignalPolicy {

    public BottomPriceSignal evaluate(BottomPriceContext context) {
        Objects.requireNonNull(context, "context");

        var priceResetScore = roundScore(
                48
                        + drawdownContribution(context.drawdownFromHighPct())
                        + reboundContribution(context.reboundFromLowPct())
                        + boundedContribution(context.volumeTrend20dPct(), 0.35, -8, 12)
                        + returnContribution(context.return30dPct()),
                15,
                90
        );
        var patternScore = switch (context.pattern().phase()) {
            case CONFIRM -> 82;
            case RETEST -> 64;
            case CANDIDATE -> 56;
            case DECLINE -> 34;
        };
        var absorptionScore = roundScore(
                48
                        + ratioContribution(context.absorptionVolumeVsRecent3dRatio(), 26, -12, 20)
                        + absorptionContribution(context, 0.7, 0.9, 18, 12, 6, -10),
                15,
                90
        );
        var volumeConfirmationScore = roundScore(
                48
                        + ratioContribution(context.candidateVolumeRatio(), 20, -10, 16)
                        + ratioContribution(context.confirmVolumeRatio(), 22, -8, 18)
                        + inverseRatioContribution(context.retestVolumeRatio(), 18, -12, 12)
                        + boundedContribution(context.volumeTrend20dPct(), 0.2, -8, 10)
                        + ratioContribution(context.absorptionVolumeVsRecent3dRatio(), 18, -8, 14)
                        + absorptionContribution(context, 0.8, 1.0, 12, 6, 6, -8),
                15,
                90
        );
        var priceBottomScore = roundScore(
                priceResetScore * 0.22
                        + patternScore * 0.28
                        + volumeConfirmationScore * 0.50,
                0,
                100
        );
        var structureState = volumeConfirmationScore >= 72 && context.pattern().phase() == BottomPatternPhase.CONFIRM
                ? BottomStructureState.STRUCTURAL_BOTTOM_POSSIBLE
                : volumeConfirmationScore >= 64 && (
                        context.pattern().phase() == BottomPatternPhase.CONFIRM
                                || context.pattern().phase() == BottomPatternPhase.RETEST)
                ? BottomStructureState.FIRST_CONFIRMATION
                : context.pattern().phase() == BottomPatternPhase.RETEST
                ? BottomStructureState.RETEST
                : volumeConfirmationScore >= 54 || context.pattern().phase() == BottomPatternPhase.CANDIDATE
                ? BottomStructureState.BOTTOM_ATTEMPT
                : BottomStructureState.NOT_BOTTOM;

        var failureRiskScore = roundScore(
                22
                        + (context.pattern().phase() == BottomPatternPhase.DECLINE ? 22 : 0)
                        + (volumeConfirmationScore < 55 ? 14 : volumeConfirmationScore < 62 ? 8 : 0)
                        + (below(context.absorptionVolumeVsRecent3dRatio(), 1) ? 10 : 0)
                        + (absorptionWorsened(context) ? 12 : 0)
                        + (context.pattern().phase() == BottomPatternPhase.RETEST
                                && context.pattern().retestGapPct() != null
                        && context.pattern().retestGapPct() < -4 ? 18 : 0)
                        + (below(context.confirmVolumeRatio(), 0.95) ? 12 : 0),
                0,
                100
        );

        return new BottomPriceSignal(
                priceResetScore,
                patternScore,
                absorptionScore,
                volumeConfirmationScore,
                priceBottomScore,
                failureRiskScore,
                structureState
        );
    }

    /**
     * Compatibility overload for captured migration fixtures. Contextual
     * company evidence is deliberately ignored: stale guidance, analyst, or B
     * score values must never alter a present-tense chart signal.
     */
    @Deprecated(forRemoval = true)
    public BottomPriceSignal evaluate(
            BottomPriceContext context,
            int ignoredCrowdingScore,
            Double ignoredEpsEstimateRevision30dPct,
            boolean ignoredGuidanceLowered
    ) {
        return evaluate(context);
    }

    private static int drawdownContribution(Double value) {
        if (value == null) return 0;
        return value <= -25 ? 18 : value <= -12 ? 9 : -6;
    }

    private static int reboundContribution(Double value) {
        if (value == null) return 0;
        if (value >= 15 && value <= 55) return 14;
        return value > 75 ? -8 : 0;
    }

    private static int returnContribution(Double value) {
        if (value == null) return 0;
        if (value >= 25) return -10;
        if (value >= 8) return 5;
        return value <= -12 ? -6 : 0;
    }

    private static double ratioContribution(Double value, double multiplier, double min, double max) {
        return value == null ? 0 : clamp((value - 1) * multiplier, min, max);
    }

    private static double inverseRatioContribution(Double value, double multiplier, double min, double max) {
        return value == null ? 0 : clamp((1 - value) * multiplier, min, max);
    }

    private static double boundedContribution(Double value, double multiplier, double min, double max) {
        return value == null ? 0 : clamp(value * multiplier, min, max);
    }

    private static int absorptionContribution(
            BottomPriceContext context,
            double strongThreshold,
            double mediumThreshold,
            int strong,
            int medium,
            int weak,
            int worse
    ) {
        var current = context.absorptionDropPct();
        var previous = context.priorDeclineDropPct();
        if (current == null || previous == null || current >= 0 || previous >= 0) return 0;
        var ratio = Math.abs(current) / Math.abs(previous);
        if (ratio <= strongThreshold) return strong;
        if (ratio <= mediumThreshold) return medium;
        if (ratio <= 1) return weak;
        return worse;
    }

    private static boolean absorptionWorsened(BottomPriceContext context) {
        var current = context.absorptionDropPct();
        var previous = context.priorDeclineDropPct();
        return current != null && previous != null && current < 0 && previous < 0
                && Math.abs(current) > Math.abs(previous);
    }

    private static boolean below(Double value, double threshold) {
        return value != null && value < threshold;
    }

    private static int roundScore(double value, double min, double max) {
        return (int) Math.round(clamp(value, min, max));
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
