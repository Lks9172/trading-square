package io.macrosquare.research.domain.narrative;

public record NarrativeManualEvidence(
        Integer geoRisk,
        Integer aiNarrativeStrength
) {
    public static NarrativeManualEvidence empty() {
        return new NarrativeManualEvidence(null, null);
    }
}
