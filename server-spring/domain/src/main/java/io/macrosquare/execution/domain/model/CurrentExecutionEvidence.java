package io.macrosquare.execution.domain.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CurrentExecutionEvidence(
        String regime,
        int regimeScore,
        Map<String, Double> rawValues,
        Map<String, Double> derivedValues,
        Map<String, Integer> targetAllocations,
        List<SignalEvidence> signals
) {
    public CurrentExecutionEvidence {
        if (regime == null || regime.isBlank()) throw new IllegalArgumentException("regime is required");
        if (regimeScore < 0 || regimeScore > 100) throw new IllegalArgumentException("regimeScore is invalid");
        rawValues = immutable(rawValues);
        derivedValues = immutable(derivedValues);
        targetAllocations = Map.copyOf(new LinkedHashMap<>(
                targetAllocations == null ? Map.of() : targetAllocations));
        signals = List.copyOf(signals == null ? List.of() : signals);
    }

    private static Map<String, Double> immutable(Map<String, Double> source) {
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(source == null ? Map.of() : source));
    }

    public record SignalEvidence(
            String asset,
            SignalAction action,
            int dataCoveragePct,
            List<String> reasons,
            List<String> unmetReasons
    ) {
        public SignalEvidence {
            if (asset == null || asset.isBlank()) throw new IllegalArgumentException("asset is required");
            if (action == null) throw new IllegalArgumentException("action is required");
            if (dataCoveragePct < 0 || dataCoveragePct > 100) {
                throw new IllegalArgumentException("dataCoveragePct is invalid");
            }
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            unmetReasons = List.copyOf(unmetReasons == null ? List.of() : unmetReasons);
        }
    }

    public enum SignalAction {
        STRONG_BUY, BUY, HOLD, REDUCE, SELL
    }
}
