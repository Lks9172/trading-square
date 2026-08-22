package io.macrosquare.research.domain.rotation;

/** Current, provider-neutral evidence used to confirm a sector leadership hand-off. */
public record SectorLeadershipConfirmationEvidence(
        int rotationScore,
        int macroFitScore,
        int mediumTermRelativeStrengthScore,
        Double shortTermRelativeStrengthPct,
        Double mediumTermRelativeStrengthPct,
        Integer earningsRevisionScore,
        Integer flowScore,
        int crowdingReliefScore,
        SectorRotationState rotationState
) {
    public SectorLeadershipConfirmationEvidence {
        requireScore(rotationScore, "rotationScore");
        requireScore(macroFitScore, "macroFitScore");
        requireScore(mediumTermRelativeStrengthScore, "mediumTermRelativeStrengthScore");
        requireScore(earningsRevisionScore, "earningsRevisionScore");
        requireScore(flowScore, "flowScore");
        requireScore(crowdingReliefScore, "crowdingReliefScore");
        requireFinite(shortTermRelativeStrengthPct, "shortTermRelativeStrengthPct");
        requireFinite(mediumTermRelativeStrengthPct, "mediumTermRelativeStrengthPct");
        if (rotationState == null) throw new IllegalArgumentException("rotationState is required");
    }

    private static void requireScore(Integer value, String field) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
