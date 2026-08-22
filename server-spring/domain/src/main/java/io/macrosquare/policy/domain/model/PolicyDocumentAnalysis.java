package io.macrosquare.policy.domain.model;

import java.util.List;
import java.util.Objects;

/** toneScore: +100 is dovish/easing, -100 is hawkish/tightening. */
public record PolicyDocumentAnalysis(
        PolicyDocument document,
        PolicyTone tone,
        int toneScore,
        int confidence,
        int dovishWeight,
        int hawkishWeight,
        List<PolicyEvidence> evidence,
        String summary
) {
    public PolicyDocumentAnalysis {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(tone, "tone");
        if (toneScore < -100 || toneScore > 100) throw new IllegalArgumentException("toneScore must be between -100 and 100");
        if (confidence < 0 || confidence > 100) throw new IllegalArgumentException("confidence must be between 0 and 100");
        if (dovishWeight < 0 || hawkishWeight < 0) throw new IllegalArgumentException("lexicon weights must be non-negative");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
    }
}
