package io.macrosquare.market.domain.regime;

import io.macrosquare.market.domain.regime.MacroRegime;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MacroRegimeAssessment(
        MacroRegime regime,
        int score,
        Map<String, Integer> components,
        LocalDate date,
        List<String> overrides
) {
    public MacroRegimeAssessment {
        if (regime == null || date == null) throw new IllegalArgumentException("regime and date are required");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be 0..100");
        components = Map.copyOf(components == null ? Map.of() : components);
        overrides = List.copyOf(overrides == null ? List.of() : overrides);
    }
}
