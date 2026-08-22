package io.macrosquare.institutional.domain.model;

/** Difference between published analyst opinion and delayed reported institutional money. */
public record InstitutionalDivergence(
        String ticker,
        String issuer,
        String sectorKey,
        double analystScore,
        double institutionalFlowScore,
        double divergenceScore,
        int managerCount,
        double aggregateShareDeltaPct,
        String signal
) {
    public InstitutionalDivergence {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        sectorKey = sectorKey == null ? "" : sectorKey;
        finiteRange(analystScore, -2, 2, "analystScore");
        finiteRange(institutionalFlowScore, -2, 2, "institutionalFlowScore");
        finiteRange(divergenceScore, -4, 4, "divergenceScore");
        if (managerCount < 1) throw new IllegalArgumentException("managerCount must be positive");
        if (!Double.isFinite(aggregateShareDeltaPct)) {
            throw new IllegalArgumentException("aggregateShareDeltaPct must be finite");
        }
        if (signal == null || signal.isBlank()) throw new IllegalArgumentException("signal is required");
    }

    private static void finiteRange(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is out of range");
        }
    }
}
