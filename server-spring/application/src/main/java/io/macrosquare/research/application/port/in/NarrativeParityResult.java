package io.macrosquare.research.application.port.in;

import io.macrosquare.research.domain.narrative.NarrativeStage;
import io.macrosquare.research.domain.narrative.NarrativeTheme;

import java.util.List;

public record NarrativeParityResult(
        NarrativeTheme theme,
        boolean matched,
        NarrativeStage expectedStage,
        Integer expectedHeatScore,
        NarrativeStage actualStage,
        Integer actualHeatScore,
        List<String> differences
) {
    public NarrativeParityResult {
        differences = List.copyOf(differences == null ? List.of() : differences);
    }
}
