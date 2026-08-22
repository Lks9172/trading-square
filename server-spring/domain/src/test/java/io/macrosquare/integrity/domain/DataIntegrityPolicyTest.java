package io.macrosquare.integrity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataIntegrityPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-07T08:00:00Z");
    private final DataIntegrityPolicy policy = new DataIntegrityPolicy(275, Duration.ofHours(2));

    @Test
    void acceptsACompleteCurrentProjection() {
        assertTrue(policy.evaluate(evidence(metrics(), NOW.minus(Duration.ofMinutes(30)))).healthy());
    }

    @Test
    void detectsPreviouslyObservedScoreActionAndUnitFailuresTogether() {
        var metrics = metrics();
        metrics.put(IntegrityMetric.NONCURRENT_SCORED_ROWS, 3L);
        metrics.put(IntegrityMetric.BUY_WITHOUT_EVIDENCE_ROWS, 2L);
        metrics.put(IntegrityMetric.SUSPICIOUS_13F_UNIT_GROUPS, 1L);
        metrics.put(IntegrityMetric.FUTURE_MARKET_ROWS, 4L);

        var report = policy.evaluate(evidence(metrics, NOW.minus(Duration.ofMinutes(30))));

        assertFalse(report.healthy());
        assertEquals(List.of(
                        IntegrityMetric.BUY_WITHOUT_EVIDENCE_ROWS.name(),
                        IntegrityMetric.FUTURE_MARKET_ROWS.name(),
                        IntegrityMetric.NONCURRENT_SCORED_ROWS.name(),
                        IntegrityMetric.SUSPICIOUS_13F_UNIT_GROUPS.name()),
                report.violations().stream().map(DataIntegrityViolation::code).sorted().toList());
        assertEquals(64, report.fingerprint().length());
    }

    @Test
    void detectsMissingUniverseRowsAndAStaleRefresh() {
        var metrics = metrics();
        metrics.put(IntegrityMetric.COMPANY_UNIVERSE_ROWS, 274L);
        metrics.put(IntegrityMetric.COMPANY_CURRENT_CALCULATION_ROWS, 274L);

        var report = policy.evaluate(evidence(metrics, NOW.minus(Duration.ofHours(3))));

        assertTrue(report.violations().stream().anyMatch(value ->
                value.code().equals(IntegrityMetric.COMPANY_UNIVERSE_ROWS.name())));
        assertTrue(report.violations().stream().anyMatch(value ->
                value.code().equals("COMPANY_SUMMARY_STALE")));
    }

    @Test
    void detectsAProviderWideScoreCollapseAndAnyMissingCurrentPriceSignal() {
        var metrics = metrics();
        metrics.put(IntegrityMetric.COMPANY_COMPARABLE_SCORE_ROWS, 219L);
        metrics.put(IntegrityMetric.COMPANY_PRICE_SIGNAL_ROWS, 274L);

        var report = policy.evaluate(evidence(metrics, NOW.minus(Duration.ofMinutes(30))));

        assertTrue(report.violations().stream().anyMatch(value ->
                value.code().equals(IntegrityMetric.COMPANY_COMPARABLE_SCORE_ROWS.name())));
        assertTrue(report.violations().stream().anyMatch(value ->
                value.code().equals(IntegrityMetric.COMPANY_PRICE_SIGNAL_ROWS.name())));
    }

    @Test
    void detectsMissingOrMisalignedSectorLeadershipEvidence() {
        var current = metrics();
        current.put(IntegrityMetric.SECTOR_HISTORY_READY_ROWS, 15L);
        current.put(IntegrityMetric.MISALIGNED_SECTOR_PRICE_ROWS, 1L);

        var report = policy.evaluate(evidence(current, NOW.minus(Duration.ofMinutes(30))));

        assertEquals(
                java.util.Set.of(
                        IntegrityMetric.SECTOR_HISTORY_READY_ROWS.name(),
                        IntegrityMetric.MISALIGNED_SECTOR_PRICE_ROWS.name()),
                report.violations().stream().map(DataIntegrityViolation::code)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void detectsMissingOrInvalidCurrentSectorRotationLedger() {
        var current = metrics();
        current.put(IntegrityMetric.CURRENT_SECTOR_ROTATION_READY_ROWS, 0L);
        current.put(IntegrityMetric.INVALID_SECTOR_ROTATION_RUN_ROWS, 1L);

        var report = policy.evaluate(evidence(current, NOW.minus(Duration.ofMinutes(30))));

        assertTrue(report.violations().stream().anyMatch(value ->
                value.code().equals(IntegrityMetric.CURRENT_SECTOR_ROTATION_READY_ROWS.name())));
        assertTrue(report.violations().stream().anyMatch(value ->
                value.code().equals(IntegrityMetric.INVALID_SECTOR_ROTATION_RUN_ROWS.name())));
    }

    @Test
    void fingerprintRemainsStableWhileTheSameIncidentCountChanges() {
        var first = metrics();
        first.put(IntegrityMetric.INVALID_COMPANY_SCORE_ROWS, 1L);
        var second = metrics();
        second.put(IntegrityMetric.INVALID_COMPANY_SCORE_ROWS, 2L);

        assertEquals(
                policy.evaluate(evidence(first, NOW)).fingerprint(),
                policy.evaluate(evidence(second, NOW)).fingerprint());
    }

    @Test
    void fingerprintChangesWhenANewIncidentClassAppears() {
        var first = metrics();
        first.put(IntegrityMetric.INVALID_COMPANY_SCORE_ROWS, 1L);
        var second = metrics();
        second.put(IntegrityMetric.INVALID_COMPANY_SCORE_ROWS, 2L);
        second.put(IntegrityMetric.FUTURE_MARKET_ROWS, 1L);

        assertFalse(policy.evaluate(evidence(first, NOW)).fingerprint().equals(
                policy.evaluate(evidence(second, NOW)).fingerprint()));
    }

    @Test
    void fingerprintDistinguishesDifferentFailedKeysFromTheSameProvider() {
        var violation = List.of(new DataIntegrityViolation(
                IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS.name(), 1, 0,
                "의사결정 원천 수집 실패"));
        var krw = new DataIntegrityReport(
                NOW, violation, List.of("YAHOO:DEGRADED:USDKRW"));
        var jpy = new DataIntegrityReport(
                NOW, violation, List.of("YAHOO:DEGRADED:USDJPY"));

        assertNotEquals(krw.fingerprint(), jpy.fingerprint());
    }

    @Test
    void fingerprintIsStableWhenFailureKeyOrderingChanges() {
        var violation = List.of(new DataIntegrityViolation(
                IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS.name(), 2, 0,
                "의사결정 원천 수집 실패"));
        var first = new DataIntegrityReport(
                NOW, violation, List.of("YAHOO:DEGRADED:USDKRW,USDJPY"));
        var reordered = new DataIntegrityReport(
                NOW, violation, List.of("YAHOO:DEGRADED:USDJPY, USDKRW"));

        assertEquals(first.fingerprint(), reordered.fingerprint());
    }

    @Test
    void fingerprintDistinguishesSourceFailureFromPersistenceCountMismatch() {
        var violation = List.of(new DataIntegrityViolation(
                IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS.name(), 1, 0,
                "의사결정 원천 수집 실패"));
        var sourceGap = new DataIntegrityReport(
                NOW, violation, List.of("YAHOO:FAILED:SOURCE_GAP:USDKRW"));
        var persistenceGap = new DataIntegrityReport(
                NOW, violation, List.of("YAHOO:FAILED:PERSISTENCE_COUNT_MISMATCH:"));

        assertNotEquals(sourceGap.fingerprint(), persistenceGap.fingerprint());
    }

    private static EnumMap<IntegrityMetric, Long> metrics() {
        var metrics = new EnumMap<IntegrityMetric, Long>(IntegrityMetric.class);
        for (var metric : IntegrityMetric.values()) metrics.put(metric, 0L);
        metrics.put(IntegrityMetric.COMPANY_UNIVERSE_ROWS, 275L);
        metrics.put(IntegrityMetric.COMPANY_CURRENT_CALCULATION_ROWS, 275L);
        metrics.put(IntegrityMetric.COMPANY_COMPARABLE_SCORE_ROWS, 236L);
        metrics.put(IntegrityMetric.COMPANY_PRICE_SIGNAL_ROWS, 275L);
        metrics.put(IntegrityMetric.ANALYST_SERIES_ROWS, 275L);
        metrics.put(IntegrityMetric.CANONICAL_MRSH_ROWS, 1L);
        metrics.put(IntegrityMetric.MARKET_COLLECTION_STATUS_ROWS, 6L);
        metrics.put(IntegrityMetric.SECTOR_PRICE_SERIES_ROWS, 16L);
        metrics.put(IntegrityMetric.SECTOR_HISTORY_READY_ROWS, 16L);
        metrics.put(IntegrityMetric.SECTOR_BENCHMARK_READY_ROWS, 1L);
        metrics.put(IntegrityMetric.SECTOR_TOTAL_RETURN_SERIES_ROWS, 16L);
        metrics.put(IntegrityMetric.SECTOR_TOTAL_RETURN_HISTORY_READY_ROWS, 16L);
        metrics.put(IntegrityMetric.SECTOR_TOTAL_RETURN_BENCHMARK_READY_ROWS, 1L);
        metrics.put(IntegrityMetric.CURRENT_SECTOR_ROTATION_READY_ROWS, 1L);
        return metrics;
    }

    private static DataIntegrityEvidence evidence(
            EnumMap<IntegrityMetric, Long> metrics,
            Instant oldest
    ) {
        return new DataIntegrityEvidence(metrics, oldest, NOW, List.of());
    }
}
