package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyAnalystEvidence;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompanyMarketExpectationsPolicyTest {

    private final CompanyMarketExpectationsPolicy policy = new CompanyMarketExpectationsPolicy();

    @Test
    void separatesProviderEpsRevisionsFromTargetUpsideHistory() {
        var evidence = new CompanyAnalystEvidence(
                1.098,
                49.06,
                2.0,
                4.0,
                6.0,
                List.of(
                        point("2026-06-18", 1.097, 41.88),
                        point("2026-06-19", 1.097, 41.88),
                        point("2026-07-19", 1.098, 49.06)
                )
        );

        var result = policy.evaluate(evidence, Instant.parse("2026-07-19T00:00:00Z"));

        assertEquals(49.06, result.estimateUpsidePct());
        assertEquals(2.0, result.estimateRevision7d());
        assertEquals(4.0, result.estimateRevision30d());
        assertEquals(6.0, result.estimateRevision90d());
        assertEquals(7.18, result.targetUpsideChange30d());
        assertEquals(0.001, result.analystScoreRevision30d());
    }

    @Test
    void excludesTodaysPointAndKeepsTheEarlierPointWhenDistancesTie() {
        var evidence = new CompanyAnalystEvidence(
                1.0,
                10.0,
                List.of(
                        point("2026-06-18", 0.4, 3.0),
                        point("2026-06-20", 0.8, 7.0),
                        point("2026-07-19", -2.0, -100.0)
                )
        );

        var result = policy.evaluate(evidence, Instant.parse("2026-07-19T00:00:00Z"));

        assertNull(result.estimateRevision30d());
        assertEquals(7.0, result.targetUpsideChange30d());
        assertEquals(0.6, result.analystScoreRevision30d());
    }

    @Test
    void returnsNullableRevisionsWithoutInventingMissingEvidence() {
        var noHistory = policy.evaluate(
                new CompanyAnalystEvidence(0.5, 12.0, List.of()),
                Instant.parse("2026-07-19T00:00:00Z")
        );
        var noCurrent = policy.evaluate(
                new CompanyAnalystEvidence(null, null, List.of(point("2026-06-19", 0.2, 4.0))),
                Instant.parse("2026-07-19T00:00:00Z")
        );

        assertEquals(12.0, noHistory.estimateUpsidePct());
        assertNull(noHistory.estimateRevision30d());
        assertNull(noHistory.targetUpsideChange30d());
        assertNull(noHistory.analystScoreRevision30d());
        assertNull(noCurrent.estimateUpsidePct());
        assertNull(noCurrent.estimateRevision30d());
        assertNull(noCurrent.targetUpsideChange30d());
        assertNull(noCurrent.analystScoreRevision30d());
    }

    @Test
    void preservesTheLegacyUtcTimeOfDayWhenChoosingTheNearestSnapshot() {
        var evidence = new CompanyAnalystEvidence(
                1.0,
                10.0,
                List.of(
                        point("2026-06-19", 0.2, 2.0),
                        point("2026-06-20", 0.7, 7.0)
                )
        );

        var result = policy.evaluate(evidence, Instant.parse("2026-07-19T18:00:00Z"));

        assertNull(result.estimateRevision30d());
        assertEquals(3.0, result.targetUpsideChange30d());
        assertEquals(0.3, result.analystScoreRevision30d());
    }

    @Test
    void doesNotMislabelAnOldSnapshotAsAThirtyDayRevision() {
        var evidence = new CompanyAnalystEvidence(
                1.0,
                10.0,
                List.of(point("2026-03-01", -1.0, -40.0))
        );

        var result = policy.evaluate(evidence, Instant.parse("2026-07-19T00:00:00Z"));

        assertNull(result.targetUpsideChange30d());
        assertNull(result.analystScoreRevision30d());
    }

    private static CompanyAnalystHistoryPoint point(String date, Double score, Double upside) {
        return new CompanyAnalystHistoryPoint(LocalDate.parse(date), score, upside);
    }
}
