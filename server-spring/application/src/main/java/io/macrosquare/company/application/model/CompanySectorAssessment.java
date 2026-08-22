package io.macrosquare.company.application.model;

import java.util.List;

/**
 * Company-owned anti-corruption projection of the research sector context.
 * Research domain types must not cross into the company application boundary.
 */
public record CompanySectorAssessment(
        String sectorId,
        String label,
        String sectorKey,
        String classification,
        Integer buyScore,
        Integer qualityScore,
        Integer appealScore,
        Integer crowdingScore,
        Integer valuationScore,
        /** Current, independently dated sector revision evidence. */
        Integer earningsRevisionScore,
        /** Slower catalog reference retained for display, never treated as current confirmation. */
        Integer referenceEarningsRevisionScore,
        Integer rotationScore,
        Integer rotationRank,
        Integer rotationUniverseSize,
        Integer rotationPercentile,
        Integer macroFitScore,
        Integer relativeStrengthScore,
        Integer fundamentalScore,
        /** Independent institutional/flow evidence when available. */
        Integer flowScore,
        /** Style/liquidity proxy retained for context, not independent confirmation. */
        Integer proxyFlowScore,
        String stance,
        String rotationState,
        String rotationLabel,
        String expectedLeadershipWindow,
        String expectedLeadershipMessage,
        List<String> reasons
) {
    public CompanySectorAssessment {
        requireText(sectorId, "sectorId");
        requireText(label, "label");
        requireText(sectorKey, "sectorKey");
        requireText(classification, "classification");
        requireScores(
                buyScore, qualityScore, appealScore, crowdingScore, valuationScore,
                earningsRevisionScore, referenceEarningsRevisionScore, rotationScore,
                rotationPercentile, macroFitScore, relativeStrengthScore,
                fundamentalScore, flowScore, proxyFlowScore);
        if (rotationRank != null && rotationRank < 1) {
            throw new IllegalArgumentException("rotationRank must be positive");
        }
        if (rotationUniverseSize != null && rotationUniverseSize < 1) {
            throw new IllegalArgumentException("rotationUniverseSize must be positive");
        }
        if (rotationRank != null && rotationUniverseSize != null && rotationRank > rotationUniverseSize) {
            throw new IllegalArgumentException("rotationRank must not exceed universe size");
        }
        stance = stance == null ? "neutral" : stance;
        rotationState = rotationState == null ? "UNKNOWN" : rotationState;
        rotationLabel = rotationLabel == null ? "" : rotationLabel;
        expectedLeadershipWindow = expectedLeadershipWindow == null ? "" : expectedLeadershipWindow;
        expectedLeadershipMessage = expectedLeadershipMessage == null ? "" : expectedLeadershipMessage;
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static void requireScores(Integer... values) {
        for (var value : values) {
            if (value != null && (value < 0 || value > 100)) {
                throw new IllegalArgumentException("score must be between 0 and 100");
            }
        }
    }
}
