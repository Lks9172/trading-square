package io.macrosquare.market.domain.signal;

import java.time.LocalDate;
import java.util.List;

public record CoreAssetSignal(
        String asset,
        CoreSignalAction action,
        int conditionsMet,
        int conditionsTotal,
        int conditionsAvailable,
        int weightedScore,
        int weightedMaxScore,
        int dataCoveragePct,
        List<String> reasons,
        List<String> unmetReasons,
        List<String> missingReasons,
        LocalDate date,
        String leverageTier
) {
    public CoreAssetSignal {
        if (asset == null || asset.isBlank() || action == null || date == null) {
            throw new IllegalArgumentException("asset, action and date are required");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        unmetReasons = List.copyOf(unmetReasons == null ? List.of() : unmetReasons);
        missingReasons = List.copyOf(missingReasons == null ? List.of() : missingReasons);
        if (conditionsMet < 0 || conditionsAvailable < conditionsMet
                || conditionsTotal < conditionsAvailable || weightedMaxScore <= 0
                || weightedScore < 0 || weightedScore > weightedMaxScore
                || dataCoveragePct < 0 || dataCoveragePct > 100) {
            throw new IllegalArgumentException("signal counters are invalid");
        }
    }
}
