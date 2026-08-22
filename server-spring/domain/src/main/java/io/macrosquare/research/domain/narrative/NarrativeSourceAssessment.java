package io.macrosquare.research.domain.narrative;

import java.time.Instant;
import java.util.List;

public record NarrativeSourceAssessment(
        NarrativeSourceCoverageStatus status,
        int qualityScore,
        int coveragePct,
        boolean legacyFallbackUsed,
        List<NarrativeExternalSignal> signals,
        List<NarrativeSourceDiagnostic> diagnostics,
        List<NarrativeSourceHistoryPoint> history,
        int observationCount,
        int revisionEventCount,
        int missingObservationCount,
        int failedObservationCount,
        Instant lastRefreshAt
) {
    public NarrativeSourceAssessment(
            NarrativeSourceCoverageStatus status,
            int qualityScore,
            int coveragePct,
            boolean legacyFallbackUsed,
            List<NarrativeExternalSignal> signals,
            List<NarrativeSourceDiagnostic> diagnostics
    ) {
        this(status, qualityScore, coveragePct, legacyFallbackUsed, signals, diagnostics,
                List.of(), 0, 0, 0, 0, null);
    }

    public NarrativeSourceAssessment {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (qualityScore < 0 || qualityScore > 100) throw new IllegalArgumentException("qualityScore is out of range");
        if (coveragePct < 0 || coveragePct > 100) throw new IllegalArgumentException("coveragePct is out of range");
        if (observationCount < 0) throw new IllegalArgumentException("observationCount cannot be negative");
        if (revisionEventCount < 0) throw new IllegalArgumentException("revisionEventCount cannot be negative");
        if (missingObservationCount < 0) {
            throw new IllegalArgumentException("missingObservationCount cannot be negative");
        }
        if (failedObservationCount < 0) {
            throw new IllegalArgumentException("failedObservationCount cannot be negative");
        }
        if (revisionEventCount > observationCount
                || missingObservationCount > observationCount
                || failedObservationCount > observationCount) {
            throw new IllegalArgumentException("source counters cannot exceed observationCount");
        }
        signals = List.copyOf(signals == null ? List.of() : signals);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
        history = List.copyOf(history == null ? List.of() : history);
    }

    public static NarrativeSourceAssessment unavailable() {
        return new NarrativeSourceAssessment(
                NarrativeSourceCoverageStatus.UNAVAILABLE, 0, 0, false, List.of(), List.of());
    }
}
