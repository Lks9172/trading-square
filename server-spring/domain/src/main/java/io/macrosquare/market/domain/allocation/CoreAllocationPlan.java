package io.macrosquare.market.domain.allocation;

import io.macrosquare.market.domain.regime.MacroRegime;

import java.time.LocalDate;
import java.util.Map;

public record CoreAllocationPlan(
        MacroRegime regime,
        int score,
        Map<String, Integer> allocations,
        boolean leverageAllowed,
        Integer buyStage,
        LocalDate date
) {
    public CoreAllocationPlan {
        if (regime == null || date == null) throw new IllegalArgumentException("regime and date are required");
        allocations = Map.copyOf(allocations);
        if (allocations.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new IllegalArgumentException("allocations must sum to 100");
        }
    }
}
