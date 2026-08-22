package io.macrosquare.policy.domain.service;

import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.policy.domain.model.PolicyTone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyToneAnalysisPolicyTest {

    private final PolicyToneAnalysisPolicy policy = new PolicyToneAnalysisPolicy();

    @Test
    void identifiesDovishEvidenceWithoutTreatingConfidenceAsForecastProbability() {
        var analysis = policy.analyze(document(
                "Inflation has eased and labor market conditions have cooled. "
                        + "The Committee decided to reduce the target range. Risks are roughly in balance."));

        assertEquals(PolicyTone.DOVISH, analysis.tone());
        assertTrue(analysis.toneScore() >= 50);
        assertTrue(analysis.confidence() > 0 && analysis.confidence() <= 100);
        assertTrue(analysis.dovishWeight() > analysis.hawkishWeight());
        assertTrue(analysis.evidence().stream().anyMatch(value -> value.phrase().contains("reduce")));
        assertTrue(analysis.evidence().stream()
                .filter(value -> value.phrase().contains("reduce"))
                .allMatch(value -> value.excerpt().toLowerCase().contains("reduce")));
    }

    @Test
    void aggregatesConflictingRecentDocumentsAsMixed() {
        var dovish = policy.analyze(document("Inflation has eased. The Committee may reduce the target range."));
        var hawkish = policy.analyze(new PolicyDocument(
                "hawk", "Federal Reserve", "FOMC Statement", PolicyDocumentType.FOMC_STATEMENT,
                Instant.parse("2026-07-15T00:00:00Z"), "https://www.federalreserve.gov/hawk.htm",
                "Inflation remains elevated. The stance remains restrictive and inflation remains too high."));

        var snapshot = policy.aggregate(
                List.of(dovish, hawkish), Instant.parse("2026-07-20T00:00:00Z"));

        assertEquals(PolicyTone.MIXED, snapshot.tone());
        assertEquals(2, snapshot.documentCount());
        assertTrue(snapshot.summary().contains("예측 확률이 아닙니다"));
    }

    @Test
    void unrelatedAgencyReleasesRemainVisibleWithoutDilutingThePolicyTone() {
        var dovish = policy.analyze(document(
                "Inflation has eased. The Committee decided to reduce the target range."));
        var unrelatedTreasury = policy.analyze(new PolicyDocument(
                "treasury", "U.S. Treasury", "Unrelated award notice", PolicyDocumentType.TREASURY_RELEASE,
                Instant.parse("2026-07-19T00:00:00Z"), "https://home.treasury.gov/news/press-releases/test",
                "The department announced an administrative award recipient."));

        var snapshot = policy.aggregate(
                List.of(unrelatedTreasury, dovish), Instant.parse("2026-07-20T00:00:00Z"));

        assertEquals(PolicyTone.DOVISH, snapshot.tone());
        assertEquals(dovish.toneScore(), snapshot.toneScore());
        assertEquals(2, snapshot.documentCount());
    }

    private static PolicyDocument document(String text) {
        return new PolicyDocument(
                "dove", "Federal Reserve", "FOMC Statement", PolicyDocumentType.FOMC_STATEMENT,
                Instant.parse("2026-07-10T00:00:00Z"), "https://www.federalreserve.gov/dove.htm", text);
    }
}
