package io.macrosquare.execution.domain.model;

import java.util.List;

public record PortfolioDriftAssessment(
        double totalDriftPct,
        List<WeightDrift> weights
) {
    public PortfolioDriftAssessment {
        if (!Double.isFinite(totalDriftPct) || totalDriftPct < 0) {
            throw new IllegalArgumentException("totalDriftPct must be finite and non-negative");
        }
        weights = List.copyOf(weights == null ? List.of() : weights);
    }

    public List<WeightDrift> exceeding(double thresholdPct) {
        return weights.stream().filter(value -> value.differencePct() >= thresholdPct).toList();
    }

    public record WeightDrift(
            String asset,
            double recommendedPct,
            double actualPct,
            double differencePct
    ) {
        public WeightDrift {
            if (asset == null || asset.isBlank()) throw new IllegalArgumentException("asset is required");
            requireFinite(recommendedPct, actualPct, differencePct);
        }

        private static void requireFinite(double... values) {
            for (var value : values) {
                if (!Double.isFinite(value) || value < 0) {
                    throw new IllegalArgumentException("portfolio drift value must be finite and non-negative");
                }
            }
        }
    }
}
