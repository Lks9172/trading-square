package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public record SectorRotationForwardWindow(
        LocalDate startOn,
        LocalDate endOn,
        int tradingSessions,
        double benchmarkReturnPct,
        double universeEqualWeightReturnPct,
        Map<String, Double> sectorReturnsPct
) {
    public SectorRotationForwardWindow {
        if (startOn == null || endOn == null || !startOn.isBefore(endOn)
                || tradingSessions < 1 || !Double.isFinite(benchmarkReturnPct)
                || !Double.isFinite(universeEqualWeightReturnPct)) {
            throw new IllegalArgumentException("forward window is invalid");
        }
        sectorReturnsPct = Map.copyOf(new LinkedHashMap<>(sectorReturnsPct));
        if (sectorReturnsPct.size() != 11 || sectorReturnsPct.values().stream().anyMatch(v -> !Double.isFinite(v))) {
            throw new IllegalArgumentException("forward sector returns are invalid");
        }
    }
}
