package io.macrosquare.research.application.port.in;

import java.util.List;

public record ResearchParityReport(
        String sourceTimestamp,
        boolean allMatched,
        int matchedNarratives,
        int totalNarratives,
        RotationParityResult rotation,
        List<NarrativeParityResult> narratives
) {
    public ResearchParityReport {
        narratives = List.copyOf(narratives);
    }
}
