package io.macrosquare.market.domain.regime;

import java.util.Map;

public record MacroRegimeEvidence(
        Map<String, Double> raw,
        Map<String, Double> derived,
        int policyDirection,
        int geopoliticalRisk,
        double smartMoneyScore
) {
    public MacroRegimeEvidence {
        raw = Map.copyOf(raw == null ? Map.of() : raw);
        derived = Map.copyOf(derived == null ? Map.of() : derived);
        policyDirection = Math.max(-2, Math.min(2, policyDirection));
        geopoliticalRisk = Math.max(0, Math.min(4, geopoliticalRisk));
        smartMoneyScore = Math.max(-2, Math.min(2, smartMoneyScore));
    }
}
