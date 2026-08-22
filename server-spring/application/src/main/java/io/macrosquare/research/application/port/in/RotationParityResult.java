package io.macrosquare.research.application.port.in;

import io.macrosquare.research.domain.rotation.SectorRotationRegime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RotationParityResult(
        boolean matched,
        SectorRotationRegime expectedRegime,
        SectorRotationRegime actualRegime,
        int expectedConfidence,
        int actualConfidence,
        Map<SectorRotationRegime, Integer> expectedScores,
        Map<SectorRotationRegime, Integer> actualScores,
        List<String> differences
) {
    public RotationParityResult {
        expectedScores = Collections.unmodifiableMap(new LinkedHashMap<>(expectedScores));
        actualScores = Collections.unmodifiableMap(new LinkedHashMap<>(actualScores));
        differences = List.copyOf(differences == null ? List.of() : differences);
    }
}
