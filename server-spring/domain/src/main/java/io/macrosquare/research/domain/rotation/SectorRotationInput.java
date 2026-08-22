package io.macrosquare.research.domain.rotation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SectorRotationInput(
        RotationMarketEvidence market,
        List<SectorRotationEvidence> sectors,
        Map<String, Integer> narrativeHeatScores
) {
    public SectorRotationInput {
        if (market == null) throw new IllegalArgumentException("market is required");
        sectors = List.copyOf(sectors == null ? List.of() : sectors);
        var heatScores = new LinkedHashMap<String, Integer>();
        if (narrativeHeatScores != null) {
            narrativeHeatScores.forEach((key, score) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException("narrative theme key is required");
                if (score == null || score < 0 || score > 100) {
                    throw new IllegalArgumentException("narrative heat score must be between 0 and 100");
                }
                heatScores.put(key, score);
            });
        }
        narrativeHeatScores = Map.copyOf(heatScores);
    }
}
