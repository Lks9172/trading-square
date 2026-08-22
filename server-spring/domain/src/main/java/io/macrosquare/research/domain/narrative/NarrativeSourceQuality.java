package io.macrosquare.research.domain.narrative;

public enum NarrativeSourceQuality {
    OFFICIAL_PRIMARY(100, 1.00),
    VERIFIED_API(90, 0.90),
    PUBLIC_API(80, 0.80),
    PUBLIC_FEED(65, 0.65),
    HTML_PROXY(35, 0.35),
    LEGACY_UNKNOWN(40, 0.40);

    private final int qualityPoints;
    private final double reliabilityWeight;

    NarrativeSourceQuality(int qualityPoints, double reliabilityWeight) {
        this.qualityPoints = qualityPoints;
        this.reliabilityWeight = reliabilityWeight;
    }

    public int qualityPoints() {
        return qualityPoints;
    }

    public double reliabilityWeight() {
        return reliabilityWeight;
    }
}
