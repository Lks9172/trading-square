package io.macrosquare.research.application.model;

import io.macrosquare.research.domain.narrative.NarrativeThemeState;
import io.macrosquare.research.domain.narrative.NarrativeSourceAssessment;

import java.util.List;

public record NarrativeThemeView(
        NarrativeThemeDefinition definition,
        String generatedAt,
        NarrativeThemeState state,
        NarrativeTrend trend,
        Integer heatDelta7d,
        Integer heatDelta30d,
        List<NarrativeHistoryPoint> heatHistory,
        NarrativeSourceAssessment sourceAssessment
) {
    public NarrativeThemeView(
            NarrativeThemeDefinition definition,
            String generatedAt,
            NarrativeThemeState state,
            NarrativeTrend trend,
            Integer heatDelta7d,
            Integer heatDelta30d,
            List<NarrativeHistoryPoint> heatHistory
    ) {
        this(
                definition, generatedAt, state, trend, heatDelta7d, heatDelta30d, heatHistory,
                NarrativeSourceAssessment.unavailable());
    }

    public NarrativeThemeView {
        if (definition == null) throw new IllegalArgumentException("definition is required");
        if (generatedAt == null || generatedAt.isBlank()) throw new IllegalArgumentException("generatedAt is required");
        if (state == null) throw new IllegalArgumentException("state is required");
        if (trend == null) throw new IllegalArgumentException("trend is required");
        heatHistory = List.copyOf(heatHistory == null ? List.of() : heatHistory);
        sourceAssessment = sourceAssessment == null
                ? NarrativeSourceAssessment.unavailable()
                : sourceAssessment;
    }
}
