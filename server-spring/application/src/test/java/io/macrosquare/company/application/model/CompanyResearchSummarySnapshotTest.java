package io.macrosquare.company.application.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyResearchSummarySnapshotTest {

    @Test
    void futureDatedPersistenceCannotRemainCurrentIndefinitely() {
        var now = Instant.parse("2026-08-07T00:00:00Z");
        var future = snapshot(now.plus(Duration.ofDays(30)));
        var toleratedClockSkew = snapshot(now.plus(Duration.ofMinutes(5)));

        assertFalse(future.scoreComparableAt(now, Duration.ofHours(24)));
        assertFalse(future.priceSignalsCurrentAt(now, Duration.ofHours(24)));
        assertTrue(toleratedClockSkew.scoreComparableAt(now, Duration.ofHours(24)));
        assertTrue(toleratedClockSkew.priceSignalsCurrentAt(now, Duration.ofHours(24)));
    }

    @Test
    void executionActionsAreNormalizedAndInvalidationAlwaysFailsClosed() {
        var current = snapshot(Instant.parse("2026-08-07T00:00:00Z"))
                .withExecutionAction("strong_buy", Instant.parse("2026-08-07T00:01:00Z"));

        assertEquals("STRONG BUY", current.executionAction());
        assertEquals("HOLD", current.withoutPriceSignals(Instant.EPOCH).executionAction());
        assertEquals("HOLD", current.quarantined("bad source", Instant.EPOCH).executionAction());
        assertThrows(IllegalArgumentException.class,
                () -> current.withExecutionAction("UNKNOWN", Instant.EPOCH));
    }

    @Test
    void rejectsCorruptScoreAndPartialSignalBundlesBeforePersistence() {
        var current = snapshot(Instant.parse("2026-08-07T00:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> new CompanyResearchSummarySnapshot(
                "TEST", LocalDate.parse("2026-06-30"), 100_000.0,
                10.0, 20.0, 5.0, 101, 75, 82, 70, 85,
                78, "BUY", 80, 25, "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-30"),
                "10-Q", 0, List.of(), 72, 75, 20, 80, "CONVICTION",
                Instant.parse("2026-08-07T00:00:00Z")
        ));
        assertThrows(IllegalArgumentException.class, () -> current.withPriceSignals(
                80, null, 10, 90, "CONVICTION", Instant.parse("2026-08-07T00:01:00Z")));
    }

    @Test
    void currentNotificationEvidenceSurvivesExecutionActionComposition() {
        var now = Instant.parse("2026-08-07T00:00:00Z");
        var current = snapshot(now).withPriceSignals(
                72, 75, 20, 80, "CONVICTION",
                LocalDate.parse("2026-08-05"), "STRONG", 86,
                List.of("거래량과 가격 구조 확인"), macdTiming(), now
        ).withExecutionAction("BUY", now.plusSeconds(1));

        assertTrue(current.notificationEvidenceCurrentAt(now.plusSeconds(2), Duration.ofHours(2)));
        assertEquals("STRONG", current.reversalStatus());
        assertEquals(86, current.reversalScore());
        assertEquals(List.of("거래량과 가격 구조 확인"), current.priceSignalReasons());
        assertEquals("BULLISH_CROSS", current.macdTiming().daily().latestCross());
    }

    private static CompanyResearchSummarySnapshot snapshot(Instant updatedAt) {
        return new CompanyResearchSummarySnapshot(
                "TEST", LocalDate.parse("2026-06-30"), 100_000.0,
                10.0, 20.0, 5.0, 80, 75, 82, 70, 85,
                78, "BUY", 80, 25, "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-30"),
                "10-Q", 0, List.of(), 72, 75, 20, 80, "CONVICTION", updatedAt
        );
    }

    private static CompanyMacdTimingSnapshot macdTiming() {
        var daily = new CompanyMacdTimingSnapshot.Timeframe(
                LocalDate.parse("2026-08-06"), "ABOVE_SIGNAL", "BULLISH_CROSS",
                LocalDate.parse("2026-08-04"), 2, "EXPANDING_POSITIVE", "BULLISH",
                LocalDate.parse("2026-08-05"), 1, true);
        var weekly = new CompanyMacdTimingSnapshot.Timeframe(
                LocalDate.parse("2026-08-06"), "ABOVE_SIGNAL", "BULLISH_CROSS",
                LocalDate.parse("2026-08-01"), 1, "CONTRACTING_POSITIVE", "NONE",
                null, null, false);
        return new CompanyMacdTimingSnapshot(daily, weekly, true);
    }
}
