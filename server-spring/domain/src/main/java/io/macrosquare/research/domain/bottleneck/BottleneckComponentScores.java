package io.macrosquare.research.domain.bottleneck;

public record BottleneckComponentScores(
        double textSignal,
        double quality,
        double concentration,
        double supplyTightness,
        double capexLinkage,
        double switchingCost
) {
}
