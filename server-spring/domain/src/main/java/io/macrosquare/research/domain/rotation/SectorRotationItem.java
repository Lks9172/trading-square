package io.macrosquare.research.domain.rotation;

import java.util.List;

public record SectorRotationItem(
        String key,
        String label,
        SectorClassification classification,
        int rotationScore,
        int macroFitScore,
        int relativeStrengthScore,
        int fundamentalScore,
        Integer valuationScore,
        Integer earningsRevisionScore,
        Integer flowScore,
        int crowdingReliefScore,
        SectorRotationState state,
        SectorRotationLabel rotationLabel,
        SectorRotationHorizon expectedLeadershipWindow,
        String expectedLeadershipMessage,
        List<String> reasons
) {
    public SectorRotationItem {
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    public SectorRotationItem withReasons(List<String> newReasons) {
        return new SectorRotationItem(
                key, label, classification, rotationScore, macroFitScore, relativeStrengthScore,
                fundamentalScore, valuationScore, earningsRevisionScore, flowScore,
                crowdingReliefScore, state, rotationLabel, expectedLeadershipWindow,
                expectedLeadershipMessage, newReasons
        );
    }
}
