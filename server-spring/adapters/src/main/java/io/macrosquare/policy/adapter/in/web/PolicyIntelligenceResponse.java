package io.macrosquare.policy.adapter.in.web;

import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;
import io.macrosquare.policy.domain.model.PolicyEvidence;
import io.macrosquare.policy.domain.model.PolicyIntelligenceSnapshot;

import java.time.Instant;
import java.util.List;

public record PolicyIntelligenceResponse(
        String status,
        Instant asOf,
        String tone,
        int toneScore,
        int confidence,
        int documentCount,
        String summary,
        List<DocumentResponse> documents,
        CalibrationResponse calibration,
        String methodology
) {
    static PolicyIntelligenceResponse from(PolicyIntelligenceSnapshot value) {
        return new PolicyIntelligenceResponse(
                value.documentCount() == 0 ? "collecting" : "ready",
                value.asOf(),
                value.tone().name(),
                value.toneScore(),
                value.confidence(),
                value.documentCount(),
                value.summary(),
                value.documents().stream().map(DocumentResponse::from).toList(),
                CalibrationResponse.from(value.calibration()),
                "Federal Reserve·U.S. Treasury·USTR 공식 원문을 설명 가능한 문구 사전으로 분석합니다. evidence confidence는 근거량이며 calibration은 과거 명시적 FOMC 금리결정에 대한 인과적 walk-forward 진단입니다. 둘 다 시장수익률 확률이 아닙니다."
        );
    }

    public record DocumentResponse(
            String id,
            String source,
            String title,
            String type,
            Instant publishedAt,
            String url,
            String tone,
            int toneScore,
            int confidence,
            int dovishWeight,
            int hawkishWeight,
            String summary,
            List<EvidenceResponse> evidence
    ) {
        static DocumentResponse from(PolicyDocumentAnalysis value) {
            var document = value.document();
            return new DocumentResponse(
                    document.id(), document.source(), document.title(), document.type().name(),
                    document.publishedAt(), document.url(), value.tone().name(), value.toneScore(),
                    value.confidence(), value.dovishWeight(), value.hawkishWeight(), value.summary(),
                    value.evidence().stream().map(EvidenceResponse::from).toList());
        }
    }

    public record EvidenceResponse(String phrase, String direction, int weight, String excerpt) {
        static EvidenceResponse from(PolicyEvidence value) {
            return new EvidenceResponse(
                    value.phrase(), value.direction().name(), value.weight(), value.excerpt());
        }
    }

    public record CalibrationResponse(
            int sampleCount,
            int calibratedConfidence,
            double walkForwardAccuracyPct,
            double brierScore,
            boolean enoughSamples,
            Instant windowStart,
            Instant windowEnd,
            String methodology
    ) {
        static CalibrationResponse from(
                io.macrosquare.policy.domain.model.PolicyCalibrationSummary value
        ) {
            return new CalibrationResponse(
                    value.sampleCount(), value.calibratedConfidence(), value.walkForwardAccuracyPct(),
                    value.brierScore(), value.enoughSamples(), value.windowStart(), value.windowEnd(),
                    value.methodology());
        }
    }
}
