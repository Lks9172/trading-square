package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyAnalystEvidence;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Keeps provider forward-EPS revisions separate from locally derived target
 * upside and analyst-rating history deltas.
 */
public final class CompanyMarketExpectationsPolicy {

    private static final int REVISION_LOOKBACK_DAYS = 30;
    private static final int MAX_TARGET_DISTANCE_DAYS = 15;

    public CompanyMarketExpectations evaluate(CompanyAnalystEvidence evidence, Instant asOf) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(asOf, "asOf");
        var previous = nearestPriorObservation(evidence, asOf, REVISION_LOOKBACK_DAYS);
        return new CompanyMarketExpectations(
                evidence.currentUpsidePct(),
                evidence.epsEstimateRevision7dPct(),
                evidence.epsEstimateRevision30dPct(),
                evidence.epsEstimateRevision90dPct(),
                delta(evidence.currentUpsidePct(), previous == null ? null : previous.upsidePct(), 2),
                delta(evidence.currentAnalystScore(), previous == null ? null : previous.analystScore(), 3)
        );
    }

    private static CompanyAnalystHistoryPoint nearestPriorObservation(
            CompanyAnalystEvidence evidence,
            Instant asOf,
            int days
    ) {
        var today = asOf.atZone(ZoneOffset.UTC).toLocalDate();
        var target = asOf.minus(Duration.ofDays(days));
        CompanyAnalystHistoryPoint nearest = null;
        long nearestDistance = Long.MAX_VALUE;
        for (var point : evidence.history()) {
            if (!point.date().isBefore(today)) continue;
            var ageDays = ChronoUnit.DAYS.between(point.date(), today);
            if (Math.abs(ageDays - days) > MAX_TARGET_DISTANCE_DAYS) continue;
            var pointInstant = point.date().atStartOfDay(ZoneOffset.UTC).toInstant();
            var distance = Math.abs(Duration.between(target, pointInstant).toMillis());
            if (distance < nearestDistance) {
                nearest = point;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static Double delta(Double current, Double previous, int scale) {
        if (current == null || previous == null) return null;
        return BigDecimal.valueOf(current - previous)
                .setScale(scale, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
