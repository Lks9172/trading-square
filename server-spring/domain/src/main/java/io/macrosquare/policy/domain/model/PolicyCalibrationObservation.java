package io.macrosquare.policy.domain.model;

import java.time.Instant;

/** Historical classifier output paired with an explicit FOMC rate decision label. */
public record PolicyCalibrationObservation(
        String documentId,
        Instant publishedAt,
        int rawConfidence,
        int toneScore,
        PolicyDecisionDirection actualDecision,
        boolean directionMatched
) {
    public PolicyCalibrationObservation {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("documentId is required");
        if (publishedAt == null) throw new IllegalArgumentException("publishedAt is required");
        if (rawConfidence < 0 || rawConfidence > 100) throw new IllegalArgumentException("rawConfidence is out of range");
        if (toneScore < -100 || toneScore > 100) throw new IllegalArgumentException("toneScore is out of range");
        if (actualDecision == null) throw new IllegalArgumentException("actualDecision is required");
    }
}
