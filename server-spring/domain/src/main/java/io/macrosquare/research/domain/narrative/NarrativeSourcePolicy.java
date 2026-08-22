package io.macrosquare.research.domain.narrative;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

public final class NarrativeSourcePolicy {

    private static final double STALE_WEIGHT_FACTOR = 0.35;
    private static final int MAX_HISTORY_POINTS = 180;

    public NarrativeSourceAssessment assess(
            NarrativeTheme theme,
            List<NarrativeSourceDefinition> definitions,
            List<NarrativeSourceObservation> observations,
            List<NarrativeExternalSignal> legacySignals,
            Instant now
    ) {
        if (theme == null) throw new IllegalArgumentException("theme is required");
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("definitions are required");
        }
        if (now == null) throw new IllegalArgumentException("now is required");

        Set<String> expectedSourceKeys = definitions.stream()
                .map(NarrativeSourceDefinition::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var relevant = observations == null ? List.<NarrativeSourceObservation>of() : observations.stream()
                .filter(value -> value.reading().theme() == theme)
                .filter(value -> expectedSourceKeys.contains(value.reading().sourceKey()))
                .toList();
        var history = sourceHistory(relevant);
        var observationCount = relevant.size();
        var revisionEventCount = (int) relevant.stream().filter(value -> value.revision() > 1).count();
        var missingObservationCount = (int) relevant.stream()
                .filter(value -> value.reading().status() == NarrativeSourceStatus.MISSING)
                .count();
        var failedObservationCount = (int) relevant.stream()
                .filter(value -> value.reading().status() == NarrativeSourceStatus.FAILED)
                .count();
        var lastRefreshAt = relevant.stream()
                .map(value -> value.reading().observedAt())
                .max(Comparator.naturalOrder())
                .orElse(null);
        var signals = new ArrayList<NarrativeExternalSignal>();
        var diagnostics = new ArrayList<NarrativeSourceDiagnostic>();
        var active = 0;
        var earnedQuality = 0d;
        var maximumQuality = definitions.stream().mapToInt(value -> value.quality().qualityPoints()).sum();

        for (var definition : definitions) {
            var values = relevant.stream()
                    .filter(value -> value.reading().sourceKey().equals(definition.key()))
                    .sorted(observationOrder())
                    .toList();
            var latestByDate = latestByDate(values);
            var latest = latestByDate.isEmpty() ? null : latestByDate.getFirst();
            var available = values.stream()
                    .filter(value -> value.reading().status() == NarrativeSourceStatus.AVAILABLE)
                    .findFirst().orElse(null);
            var ageHours = available == null ? null : nonNegativeHours(available.reading().observedAt(), now);
            var effectiveStatus = effectiveStatus(definition, latest, available, now);
            var effectiveWeight = switch (effectiveStatus) {
                case AVAILABLE -> definition.quality().reliabilityWeight();
                case STALE -> definition.quality().reliabilityWeight() * STALE_WEIGHT_FACTOR;
                case MISSING, FAILED -> 0d;
            };
            var selected = effectiveWeight > 0 ? available : null;
            if (selected != null) {
                active++;
                earnedQuality += definition.quality().qualityPoints()
                        * (effectiveStatus == NarrativeSourceStatus.AVAILABLE ? 1 : STALE_WEIGHT_FACTOR);
            }
            var value = selected == null ? null : selected.reading().value();
            var score = selected == null ? 5d : selected.reading().score();
            var detail = selected == null
                    ? latest == null ? "아직 수집된 관측치가 없습니다." : latest.reading().detail()
                    : selected.reading().detail();
            var sourceUrl = selected == null
                    ? latest == null ? "" : latest.reading().sourceUrl()
                    : selected.reading().sourceUrl();
            Integer signalRevision = selected != null
                    ? Integer.valueOf(selected.revision())
                    : latest == null ? null : Integer.valueOf(latest.revision());
            signals.add(new NarrativeExternalSignal(
                    definition.key(), definition.label(), value, score, detail,
                    definition.quality(), effectiveStatus,
                    selected == null ? latest == null ? null : latest.reading().observedAt()
                            : selected.reading().observedAt(),
                    signalRevision,
                    effectiveWeight, sourceUrl
            ));
            diagnostics.add(new NarrativeSourceDiagnostic(
                    definition.key(), definition.label(), definition.quality(), effectiveStatus,
                    latest == null ? null : latest.reading().observedAt(),
                    available == null ? null : available.reading().observedAt(),
                    ageHours,
                    latest == null ? null : latest.revision(),
                    missingStreak(latestByDate),
                    value,
                    selected == null ? null : score,
                    detail,
                    sourceUrl,
                    effectiveWeight
            ));
        }

        // Legacy proxy values are a bootstrap-only bridge. Once any native
        // observation exists, an outage must remain visible as MISSING/FAILED
        // instead of silently restoring an older, lower-quality score.
        if (relevant.isEmpty()
                && legacySignals != null
                && legacySignals.stream().anyMatch(value -> value.weight() > 0)) {
            return new NarrativeSourceAssessment(
                    NarrativeSourceCoverageStatus.DEGRADED,
                    20,
                    0,
                    true,
                    legacySignals,
                    diagnostics
            );
        }
        var coverage = (int) Math.round(active * 100d / definitions.size());
        var quality = maximumQuality == 0 ? 0 : (int) Math.round(earnedQuality * 100d / maximumQuality);
        var status = active == 0
                ? NarrativeSourceCoverageStatus.UNAVAILABLE
                : coverage >= 67 && quality >= 55
                        ? NarrativeSourceCoverageStatus.HEALTHY
                        : NarrativeSourceCoverageStatus.DEGRADED;
        return new NarrativeSourceAssessment(
                status, quality, coverage, false, signals, diagnostics, history,
                observationCount, revisionEventCount, missingObservationCount,
                failedObservationCount, lastRefreshAt);
    }

    private static List<NarrativeSourceHistoryPoint> sourceHistory(
            List<NarrativeSourceObservation> observations
    ) {
        return observations.stream()
                .sorted(Comparator
                        .comparing((NarrativeSourceObservation value) -> value.reading().observedAt())
                        .reversed()
                        .thenComparing(Comparator.comparingInt(NarrativeSourceObservation::revision).reversed())
                        .thenComparing(value -> value.reading().sourceKey()))
                .limit(MAX_HISTORY_POINTS)
                .sorted(Comparator
                        .comparing((NarrativeSourceObservation value) -> value.reading().observedAt())
                        .thenComparing(value -> value.reading().sourceKey())
                        .thenComparingInt(NarrativeSourceObservation::revision))
                .map(value -> {
                    var reading = value.reading();
                    return new NarrativeSourceHistoryPoint(
                            reading.sourceKey(), reading.label(), reading.observationDate(),
                            reading.observedAt(), value.revision(), reading.quality(), reading.status(),
                            reading.value(), reading.status() == NarrativeSourceStatus.AVAILABLE
                                    ? reading.score()
                                    : null,
                            reading.detail(), reading.sourceUrl());
                })
                .toList();
    }

    private static Comparator<NarrativeSourceObservation> observationOrder() {
        return Comparator.comparing((NarrativeSourceObservation value) -> value.reading().observationDate())
                .reversed()
                .thenComparing(Comparator.comparingInt(NarrativeSourceObservation::revision).reversed())
                .thenComparing(value -> value.reading().observedAt(), Comparator.reverseOrder());
    }

    private static List<NarrativeSourceObservation> latestByDate(List<NarrativeSourceObservation> values) {
        var byDate = new LinkedHashMap<LocalDate, NarrativeSourceObservation>();
        values.forEach(value -> byDate.putIfAbsent(value.reading().observationDate(), value));
        return List.copyOf(byDate.values());
    }

    private static NarrativeSourceStatus effectiveStatus(
            NarrativeSourceDefinition definition,
            NarrativeSourceObservation latest,
            NarrativeSourceObservation available,
            Instant now
    ) {
        if (available == null) {
            return latest == null ? NarrativeSourceStatus.MISSING : latest.reading().status();
        }
        var age = nonNegativeDuration(available.reading().observedAt(), now);
        if (latest == available && age.compareTo(definition.staleAfter()) <= 0) {
            return NarrativeSourceStatus.AVAILABLE;
        }
        if (age.compareTo(definition.maximumFallbackAge()) <= 0) return NarrativeSourceStatus.STALE;
        // An AVAILABLE row is only the collector status at observation time.
        // Once it exceeds the maximum fallback age it must not become fresh
        // again merely because it is also the latest row.
        return latest == null || latest == available
                ? NarrativeSourceStatus.MISSING
                : latest.reading().status();
    }

    private static int missingStreak(List<NarrativeSourceObservation> latestByDate) {
        var streak = 0;
        for (var value : latestByDate) {
            if (value.reading().status() == NarrativeSourceStatus.AVAILABLE) break;
            streak++;
        }
        return streak;
    }

    private static long nonNegativeHours(Instant observedAt, Instant now) {
        return nonNegativeDuration(observedAt, now).toHours();
    }

    private static Duration nonNegativeDuration(Instant observedAt, Instant now) {
        var value = Duration.between(observedAt, now);
        return value.isNegative() ? Duration.ZERO : value;
    }
}
