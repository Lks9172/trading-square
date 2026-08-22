package io.macrosquare.policy.domain.service;

import io.macrosquare.policy.domain.model.PolicyCalibrationObservation;
import io.macrosquare.policy.domain.model.PolicyCalibrationSummary;
import io.macrosquare.policy.domain.model.PolicyDecisionDirection;
import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;
import io.macrosquare.policy.domain.model.PolicyDocumentType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Extracts explicit rate-decision labels and calibrates confidence without future leakage. */
public final class PolicyConfidenceCalibrationPolicy {

    private static final int MINIMUM_CALIBRATION_SAMPLES = 20;

    public PolicyCalibrationObservation observe(PolicyDocumentAnalysis analysis) {
        if (analysis.document().type() != PolicyDocumentType.FOMC_STATEMENT) return null;
        var text = analysis.document().text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        var actual = decision(text);
        if (actual == null) return null;
        var predicted = analysis.toneScore() >= 15 ? PolicyDecisionDirection.DOVISH
                : analysis.toneScore() <= -15 ? PolicyDecisionDirection.HAWKISH
                : PolicyDecisionDirection.NEUTRAL;
        return new PolicyCalibrationObservation(
                analysis.document().id(), analysis.document().publishedAt(), analysis.confidence(),
                analysis.toneScore(), actual, predicted == actual);
    }

    public PolicyCalibrationSummary calibrate(
            List<PolicyCalibrationObservation> observations,
            int currentRawConfidence
    ) {
        if (observations == null || observations.isEmpty()) return PolicyCalibrationSummary.unavailable();
        var ordered = observations.stream()
                .sorted(Comparator.comparing(PolicyCalibrationObservation::publishedAt)
                        .thenComparing(PolicyCalibrationObservation::documentId))
                .toList();
        var prior = new ArrayList<PolicyCalibrationObservation>();
        var correct = 0;
        var brier = 0.0;
        var evaluated = 0;
        for (var observation : ordered) {
            if (!prior.isEmpty()) {
                var probability = calibratedProbability(prior, observation.rawConfidence());
                var outcome = observation.directionMatched() ? 1.0 : 0.0;
                brier += Math.pow(probability - outcome, 2);
                evaluated++;
            }
            if (observation.directionMatched()) correct++;
            prior.add(observation);
        }
        var calibrated = (int) Math.round(calibratedProbability(ordered, currentRawConfidence) * 100);
        return new PolicyCalibrationSummary(
                ordered.size(), calibrated, correct * 100.0 / ordered.size(),
                evaluated == 0 ? 0 : brier / evaluated,
                ordered.size() >= MINIMUM_CALIBRATION_SAMPLES,
                ordered.getFirst().publishedAt(), ordered.getLast().publishedAt(),
                "각 FOMC 성명 이전 관측치만 사용하는 walk-forward 보정입니다. 정답은 성명의 명시적 금리 인상·동결·인하 결정이며 시장수익률 확률이 아닙니다.");
    }

    private static double calibratedProbability(
            List<PolicyCalibrationObservation> prior,
            int rawConfidence
    ) {
        var bucket = rawConfidence / 10;
        var matching = prior.stream().filter(value -> value.rawConfidence() / 10 == bucket).toList();
        var population = matching.size() >= 5 ? matching : prior;
        var successes = population.stream().filter(PolicyCalibrationObservation::directionMatched).count();
        // Beta(2,2) shrinkage avoids extreme probabilities in sparse buckets.
        return (successes + 2.0) / (population.size() + 4.0);
    }

    private static PolicyDecisionDirection decision(String text) {
        if (containsAny(text, "decided to lower the target range", "decided to reduce the target range",
                "lowered the target range", "reduced the target range")) {
            return PolicyDecisionDirection.DOVISH;
        }
        if (containsAny(text, "decided to raise the target range", "raised the target range")) {
            return PolicyDecisionDirection.HAWKISH;
        }
        if (containsAny(text, "decided to maintain the target range", "decided to keep the target range",
                "maintain the target range", "kept the target range")) {
            return PolicyDecisionDirection.NEUTRAL;
        }
        return null;
    }

    private static boolean containsAny(String text, String... values) {
        for (var value : values) if (text.contains(value)) return true;
        return false;
    }
}
