package io.macrosquare.execution.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Transport-neutral market input required by the weekly investment review. */
public record WeeklyReviewMarketContext(
        Instant snapshotAt,
        String regime,
        int regimeScore,
        Map<String, Integer> recommendedAllocations,
        List<MarketSignal> signals,
        List<String> warnings,
        List<MarketEvent> events
) {
    public WeeklyReviewMarketContext {
        if (snapshotAt == null) throw new IllegalArgumentException("snapshotAt is required");
        regime = regime == null || regime.isBlank() ? "UNKNOWN" : regime;
        recommendedAllocations = Map.copyOf(recommendedAllocations == null ? Map.of() : recommendedAllocations);
        signals = List.copyOf(signals == null ? List.of() : signals);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        events = List.copyOf(events == null ? List.of() : events);
    }

    public record MarketSignal(
            String asset,
            String action,
            int conditionsMet,
            int conditionsTotal,
            int dataCoveragePct,
            List<String> missingReasons,
            List<String> reasons,
            List<String> unmetReasons
    ) {
        public MarketSignal {
            if (asset == null || asset.isBlank()) throw new IllegalArgumentException("asset is required");
            action = action == null || action.isBlank() ? "HOLD" : action;
            if (dataCoveragePct < 0 || dataCoveragePct > 100) dataCoveragePct = 0;
            missingReasons = List.copyOf(missingReasons == null ? List.of() : missingReasons);
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            unmetReasons = List.copyOf(unmetReasons == null ? List.of() : unmetReasons);
        }
    }

    public record MarketEvent(
            LocalDate date,
            String name,
            String category,
            String importance
    ) {
        public MarketEvent {
            if (date == null || name == null || name.isBlank()) {
                throw new IllegalArgumentException("event date and name are required");
            }
            category = category == null ? "OTHER" : category;
            importance = importance == null ? "medium" : importance;
        }
    }
}
