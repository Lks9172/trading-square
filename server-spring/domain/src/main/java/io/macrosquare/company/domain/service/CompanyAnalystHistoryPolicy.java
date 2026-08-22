package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Pure policy for the legacy-compatible UTC daily analyst-history upsert. */
public final class CompanyAnalystHistoryPolicy {

    public List<CompanyAnalystHistoryPoint> recordDaily(
            List<CompanyAnalystHistoryPoint> existing,
            LocalDate observationDate,
            CompanyAnalystConsensus consensus,
            int retentionPoints
    ) {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(observationDate, "observationDate");
        Objects.requireNonNull(consensus, "consensus");
        if (retentionPoints < 1) throw new IllegalArgumentException("retentionPoints must be positive");
        if (consensus.analystScore() == null && consensus.upsidePct() == null) {
            throw new IllegalArgumentException("current analyst consensus is unavailable");
        }

        // The relational store owns one observation per ticker/day. Legacy
        // seed duplicates are normalized here so they can never abort a full
        // ticker refresh or make revision deltas depend on input ordering.
        var byDate = new LinkedHashMap<LocalDate, CompanyAnalystHistoryPoint>();
        existing.stream()
                .map(point -> Objects.requireNonNull(point, "history point"))
                .sorted(Comparator.comparing(CompanyAnalystHistoryPoint::date))
                .forEach(point -> byDate.put(point.date(), point));
        byDate.put(observationDate, new CompanyAnalystHistoryPoint(
                observationDate,
                consensus.analystScore(),
                consensus.upsidePct(),
                consensus.epsEstimateRevision7dPct(),
                consensus.epsEstimateRevision30dPct(),
                consensus.epsEstimateRevision90dPct()
        ));
        var next = byDate.values().stream()
                .sorted(Comparator.comparing(CompanyAnalystHistoryPoint::date))
                .toList();
        if (next.size() <= retentionPoints) return List.copyOf(next);
        return List.copyOf(next.subList(next.size() - retentionPoints, next.size()));
    }
}
