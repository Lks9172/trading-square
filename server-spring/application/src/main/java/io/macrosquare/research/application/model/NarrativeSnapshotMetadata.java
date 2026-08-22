package io.macrosquare.research.application.model;

import java.util.List;

public record NarrativeSnapshotMetadata(
        NarrativeThemeDefinition definition,
        String generatedAt,
        NarrativeTrend trend,
        Integer heatDelta7d,
        Integer heatDelta30d,
        List<NarrativeHistoryPoint> heatHistory
) {
    public NarrativeSnapshotMetadata {
        if (definition == null) throw new IllegalArgumentException("definition is required");
        if (generatedAt == null || generatedAt.isBlank()) throw new IllegalArgumentException("generatedAt is required");
        if (trend == null) throw new IllegalArgumentException("trend is required");
        heatHistory = List.copyOf(heatHistory == null ? List.of() : heatHistory);
    }
}
