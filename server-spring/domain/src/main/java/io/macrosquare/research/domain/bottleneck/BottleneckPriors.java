package io.macrosquare.research.domain.bottleneck;

public record BottleneckPriors(
        Double concentration,
        Double supplyTightness,
        Double capexLinkage,
        Double switchingCost
) {
    public BottleneckPriors {
        requireScore(concentration, "concentration");
        requireScore(supplyTightness, "supplyTightness");
        requireScore(capexLinkage, "capexLinkage");
        requireScore(switchingCost, "switchingCost");
    }

    private static void requireScore(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 10)) {
            throw new IllegalArgumentException(field + " must be between 0 and 10");
        }
    }
}
