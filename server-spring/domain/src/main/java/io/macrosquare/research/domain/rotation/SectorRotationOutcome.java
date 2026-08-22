package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record SectorRotationOutcome(
        UUID runId,
        String sectorKey,
        int tradingSessions,
        LocalDate startOn,
        LocalDate endOn,
        double sectorReturnPct,
        double benchmarkReturnPct,
        double universeEqualWeightReturnPct
) {
    public SectorRotationOutcome {
        Objects.requireNonNull(runId, "runId");
        if (sectorKey == null || !sectorKey.matches("SECTOR_XL[A-Z]{1,2}")
                || tradingSessions != 21 && tradingSessions != 63 && tradingSessions != 126
                || startOn == null || endOn == null || !startOn.isBefore(endOn)
                || !Double.isFinite(sectorReturnPct) || !Double.isFinite(benchmarkReturnPct)
                || !Double.isFinite(universeEqualWeightReturnPct)) {
            throw new IllegalArgumentException("sector rotation outcome is invalid");
        }
    }

    public double benchmarkExcessReturnPct() {
        return sectorReturnPct - benchmarkReturnPct;
    }

    public double universeExcessReturnPct() {
        return sectorReturnPct - universeEqualWeightReturnPct;
    }
}
