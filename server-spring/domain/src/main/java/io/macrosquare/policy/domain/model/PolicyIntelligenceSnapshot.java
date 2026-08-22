package io.macrosquare.policy.domain.model;

import java.time.Instant;
import java.util.List;

public record PolicyIntelligenceSnapshot(
        Instant asOf,
        PolicyTone tone,
        int toneScore,
        int confidence,
        int documentCount,
        String summary,
        List<PolicyDocumentAnalysis> documents,
        PolicyCalibrationSummary calibration
) {
    public PolicyIntelligenceSnapshot {
        documents = List.copyOf(documents);
        if (calibration == null) calibration = PolicyCalibrationSummary.unavailable();
    }

    public PolicyIntelligenceSnapshot(
            Instant asOf,
            PolicyTone tone,
            int toneScore,
            int confidence,
            int documentCount,
            String summary,
            List<PolicyDocumentAnalysis> documents
    ) {
        this(asOf, tone, toneScore, confidence, documentCount, summary, documents,
                PolicyCalibrationSummary.unavailable());
    }
}
