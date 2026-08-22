package io.macrosquare.integrity.application.service;

import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.application.port.out.PublishDataIntegrityIncidentPort;
import io.macrosquare.integrity.domain.DataIntegrityEvidence;
import io.macrosquare.integrity.domain.DataIntegrityPolicy;
import io.macrosquare.integrity.domain.DataIntegrityReport;
import io.macrosquare.integrity.domain.IntegrityMetric;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckDataIntegrityServiceTest {

    @Test
    void publishesABoundedActionableMessageForANewRecurrence() {
        var now = Instant.parse("2026-08-07T08:00:00Z");
        var metrics = healthyMetrics();
        metrics.put(IntegrityMetric.BUY_WITHOUT_EVIDENCE_ROWS, 2L);
        var evidence = new DataIntegrityEvidence(
                metrics, now.minusSeconds(60), now, List.of("YAHOO:FAILED:PRICE"));
        var alert = new AtomicReference<String>();
        PublishDataIntegrityIncidentPort publisher = new PublishDataIntegrityIncidentPort() {
            @Override
            public IntegrityIncidentTransition transition(
                    DataIntegrityReport report,
                    String alertText,
                    String recoveryText,
                    Instant at
            ) {
                alert.set(alertText);
                return IntegrityIncidentTransition.NEW_ALERT;
            }
        };
        var service = new CheckDataIntegrityService(
                () -> evidence,
                publisher,
                new DataIntegrityPolicy(2, Duration.ofHours(2)),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        var result = service.check("regression-test");

        assertEquals(IntegrityIncidentTransition.NEW_ALERT, result.transition());
        assertTrue(alert.get().contains("BUY_WITHOUT_EVIDENCE_ROWS"));
        assertTrue(alert.get().contains("YAHOO:FAILED:PRICE"));
        assertTrue(alert.get().contains("DB/도메인 가드가 차단"));
    }

    private static EnumMap<IntegrityMetric, Long> healthyMetrics() {
        var result = new EnumMap<IntegrityMetric, Long>(IntegrityMetric.class);
        for (var metric : IntegrityMetric.values()) result.put(metric, 0L);
        result.put(IntegrityMetric.COMPANY_UNIVERSE_ROWS, 2L);
        result.put(IntegrityMetric.COMPANY_CURRENT_CALCULATION_ROWS, 2L);
        result.put(IntegrityMetric.ANALYST_SERIES_ROWS, 2L);
        result.put(IntegrityMetric.CANONICAL_MRSH_ROWS, 1L);
        result.put(IntegrityMetric.MARKET_COLLECTION_STATUS_ROWS, 6L);
        result.put(IntegrityMetric.SECTOR_PRICE_SERIES_ROWS, 16L);
        result.put(IntegrityMetric.SECTOR_HISTORY_READY_ROWS, 16L);
        result.put(IntegrityMetric.SECTOR_BENCHMARK_READY_ROWS, 1L);
        result.put(IntegrityMetric.SECTOR_TOTAL_RETURN_SERIES_ROWS, 16L);
        result.put(IntegrityMetric.SECTOR_TOTAL_RETURN_HISTORY_READY_ROWS, 16L);
        result.put(IntegrityMetric.SECTOR_TOTAL_RETURN_BENCHMARK_READY_ROWS, 1L);
        result.put(IntegrityMetric.CURRENT_SECTOR_ROTATION_READY_ROWS, 1L);
        return result;
    }
}
