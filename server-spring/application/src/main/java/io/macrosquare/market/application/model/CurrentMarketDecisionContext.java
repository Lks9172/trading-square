package io.macrosquare.market.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Market-owned contract exposed to outer adapters for cross-context composition. */
public record CurrentMarketDecisionContext(
        Instant calculatedAt,
        String regime,
        int regimeScore,
        Map<String, Double> rawValues,
        Map<String, Double> derivedValues,
        Map<String, LocalDate> rawObservedOn,
        Map<String, LocalDate> derivedObservedOn,
        Map<String, Integer> targetAllocations,
        List<Signal> signals
) {
    public CurrentMarketDecisionContext {
        rawValues = Map.copyOf(rawValues);
        derivedValues = Map.copyOf(derivedValues);
        rawObservedOn = Map.copyOf(rawObservedOn);
        derivedObservedOn = Map.copyOf(derivedObservedOn);
        targetAllocations = Map.copyOf(targetAllocations);
        signals = List.copyOf(signals);
    }

    public CurrentMarketDecisionContext(
            Instant calculatedAt,
            String regime,
            int regimeScore,
            Map<String, Double> rawValues,
            Map<String, Double> derivedValues,
            Map<String, Integer> targetAllocations,
            List<Signal> signals
    ) {
        this(calculatedAt, regime, regimeScore, rawValues, derivedValues, Map.of(), Map.of(),
                targetAllocations, signals);
    }

    public record Signal(
            String asset,
            String action,
            int dataCoveragePct,
            List<String> reasons,
            List<String> unmetReasons
    ) {
        public Signal {
            reasons = List.copyOf(reasons);
            unmetReasons = List.copyOf(unmetReasons);
        }
    }
}
