package io.macrosquare.research.domain.rotation;

public record SectorRotationOutlookBucket(
        String label,
        String sectorKey,
        int rotationScore,
        SectorRotationState state,
        SectorRotationLabel rotationLabel,
        SectorRotationHorizon expectedLeadershipWindow,
        String expectedLeadershipMessage,
        String note
) {
}
