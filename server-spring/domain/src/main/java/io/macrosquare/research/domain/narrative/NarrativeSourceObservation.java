package io.macrosquare.research.domain.narrative;

public record NarrativeSourceObservation(
        NarrativeSourceReading reading,
        int revision
) {
    public NarrativeSourceObservation {
        if (reading == null) throw new IllegalArgumentException("reading is required");
        if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    }
}
