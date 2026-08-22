package io.macrosquare.policy.domain.service;

import io.macrosquare.policy.domain.model.PolicyCalibrationObservation;
import io.macrosquare.policy.domain.model.PolicyDecisionDirection;
import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyConfidenceCalibrationPolicyTest {

    @Test
    void extractsOnlyExplicitFomcDecisions() {
        var tone = new PolicyToneAnalysisPolicy();
        var analysis = tone.analyze(new PolicyDocument(
                "statement", "Federal Reserve", "FOMC statement", PolicyDocumentType.FOMC_STATEMENT,
                Instant.parse("2026-07-01T18:00:00Z"),
                "https://www.federalreserve.gov/statement.htm",
                "The Committee decided to lower the target range. Inflation has eased."));

        var observation = new PolicyConfidenceCalibrationPolicy().observe(analysis);

        assertEquals(PolicyDecisionDirection.DOVISH, observation.actualDecision());
        assertTrue(observation.directionMatched());
    }

    @Test
    void usesChronologicalWalkForwardCalibrationAndRequiresTwentySamples() {
        var start = Instant.parse("2020-01-01T00:00:00Z");
        var observations = new ArrayList<PolicyCalibrationObservation>();
        for (var index = 0; index < 20; index++) {
            observations.add(new PolicyCalibrationObservation(
                    "fomc-" + index, start.plus(index, ChronoUnit.DAYS), 80, 50,
                    PolicyDecisionDirection.DOVISH, index < 16));
        }

        var summary = new PolicyConfidenceCalibrationPolicy().calibrate(observations, 80);

        assertEquals(20, summary.sampleCount());
        assertEquals(75, summary.calibratedConfidence());
        assertEquals(80.0, summary.walkForwardAccuracyPct(), 1e-9);
        assertTrue(summary.enoughSamples());
        assertTrue(summary.brierScore() > 0 && summary.brierScore() < 1);
    }
}
