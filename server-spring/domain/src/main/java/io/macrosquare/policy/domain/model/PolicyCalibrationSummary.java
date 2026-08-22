package io.macrosquare.policy.domain.model;

import java.time.Instant;

/** Causal walk-forward calibration diagnostics; not a market-return forecast probability. */
public record PolicyCalibrationSummary(
        int sampleCount,
        int calibratedConfidence,
        double walkForwardAccuracyPct,
        double brierScore,
        boolean enoughSamples,
        Instant windowStart,
        Instant windowEnd,
        String methodology
) {
    public PolicyCalibrationSummary {
        if (sampleCount < 0) throw new IllegalArgumentException("sampleCount must be non-negative");
        if (calibratedConfidence < 0 || calibratedConfidence > 100) {
            throw new IllegalArgumentException("calibratedConfidence is out of range");
        }
        if (!Double.isFinite(walkForwardAccuracyPct) || walkForwardAccuracyPct < 0 || walkForwardAccuracyPct > 100) {
            throw new IllegalArgumentException("walkForwardAccuracyPct is out of range");
        }
        if (!Double.isFinite(brierScore) || brierScore < 0 || brierScore > 1) {
            throw new IllegalArgumentException("brierScore is out of range");
        }
        methodology = methodology == null ? "" : methodology;
    }

    public static PolicyCalibrationSummary unavailable() {
        return new PolicyCalibrationSummary(
                0, 0, 0, 0, false, null, null,
                "명시적 FOMC 금리결정 정답셋을 수집 중입니다.");
    }
}
