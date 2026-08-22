package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.List;

public record DeepBottomSignal(
        int score,
        DeepBottomState state,
        BottomActionBias actionBias,
        LocalDate signalDate,
        Integer daysSinceSignal,
        String summary,
        Double recentVolumeRatio,
        Double contractionRatio,
        Double drawdown120dPct,
        Double ma20GapPct,
        Double recentDrop3dPct,
        List<String> reasons,
        List<String> cautions
) {
    public DeepBottomSignal {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
    }
}
