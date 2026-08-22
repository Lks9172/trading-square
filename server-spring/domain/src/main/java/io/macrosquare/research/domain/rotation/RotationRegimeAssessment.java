package io.macrosquare.research.domain.rotation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RotationRegimeAssessment(
        SectorRotationRegime regime,
        int confidence,
        Map<SectorRotationRegime, Integer> regimeScores
) {
    public RotationRegimeAssessment {
        if (regime == null) throw new IllegalArgumentException("regime is required");
        if (confidence < 0 || confidence > 100) throw new IllegalArgumentException("confidence must be between 0 and 100");
        regimeScores = Collections.unmodifiableMap(new LinkedHashMap<>(regimeScores));
    }
}
