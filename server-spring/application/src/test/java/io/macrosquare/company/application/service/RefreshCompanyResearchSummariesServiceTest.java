package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystUniversePort;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshCompanyResearchSummariesServiceTest {

    @Test
    void refreshReportCannotHideAnUnaccountedCompany() {
        assertThrows(IllegalArgumentException.class, () ->
                new io.macrosquare.company.application.port.in.RefreshCompanyResearchSummariesUseCase.RefreshReport(
                        2, 1, List.of()));
    }

    @Test
    void quarantinesAllOldScoresAndPriceSignalsWhenAnyTickerRefreshFails() {
        LoadCompanyAnalystUniversePort universe = () -> List.of("ANY");
        var repository = new CapturingRepository(currentSnapshot());
        var now = Instant.parse("2026-08-07T03:00:00Z");
        var service = new RefreshCompanyResearchSummariesService(
                universe,
                ticker -> { throw new IllegalStateException("source basis mismatch"); },
                ticker -> { throw new AssertionError("price signals must not run after core failure"); },
                repository,
                Runnable::run,
                Clock.fixed(now, ZoneOffset.UTC),
                OperationalEventSink.noop()
        );

        var report = service.refreshAll();
        var quarantined = repository.saved.get();

        assertEquals(1, report.attempted());
        assertEquals(0, report.written());
        assertFalse(quarantined.valuationEligible());
        assertEquals("UNAVAILABLE", quarantined.fundamentalsStatus());
        assertNull(quarantined.totalScore());
        assertNull(quarantined.buyScore());
        assertNull(quarantined.evToSales());
        assertNull(quarantined.confirmedBottomScore());
        assertNull(quarantined.confirmedBottomState());
        assertEquals(now, quarantined.updatedAt());
        assertTrue(quarantined.scoreWarnings().stream().anyMatch(value -> value.contains("무효화")));
    }

    @Test
    void snapshotQuarantineIsTickerAgnosticAndRemovesEveryDerivedField() {
        var quarantined = currentSnapshot().quarantined("cross-source mismatch", Instant.EPOCH);

        assertEquals("ANY", quarantined.ticker());
        assertNull(quarantined.totalScore());
        assertNull(quarantined.growthScore());
        assertNull(quarantined.qualityScore());
        assertNull(quarantined.valuationScore());
        assertNull(quarantined.balanceSheetScore());
        assertNull(quarantined.buyScore());
        assertNull(quarantined.priceBottomScore());
        assertNull(quarantined.volumeConfirmationScore());
        assertNull(quarantined.failureRiskScore());
        assertNull(quarantined.confirmedBottomScore());
    }

    @Test
    void executorSubmissionFailureImmediatelyQuarantinesThePreviousSnapshot() {
        var repository = new CapturingRepository(currentSnapshot());
        var now = Instant.parse("2026-08-07T03:00:00Z");
        var service = new RefreshCompanyResearchSummariesService(
                () -> List.of("ANY"),
                ticker -> { throw new AssertionError("rejected task must not run"); },
                ticker -> { throw new AssertionError("rejected task must not run"); },
                repository,
                command -> { throw new java.util.concurrent.RejectedExecutionException("saturated"); },
                Clock.fixed(now, ZoneOffset.UTC),
                OperationalEventSink.noop()
        );

        var report = service.refreshAll();
        var quarantined = repository.saved.get();

        assertEquals(1, report.attempted());
        assertEquals(0, report.written());
        assertEquals("UNAVAILABLE", quarantined.fundamentalsStatus());
        assertEquals("HOLD", quarantined.executionAction());
        assertNull(quarantined.totalScore());
        assertNull(quarantined.confirmedBottomScore());
        assertEquals(now, quarantined.updatedAt());
    }

    @Test
    void retriesMissingPriceBundlesBeforeHealthyRowsWithoutReorderingPeers() {
        var healthy = currentSnapshot();
        var missing = healthy.withoutPriceSignals(Instant.parse("2026-08-07T02:30:00Z"));

        var ordered = RefreshCompanyResearchSummariesService.prioritizeMissingPriceSignals(
                List.of("HEALTHY-B", "MISSING", "ABSENT", "HEALTHY-A"),
                Map.of("HEALTHY-B", healthy, "MISSING", missing, "HEALTHY-A", healthy)
        );

        assertEquals(List.of("MISSING", "ABSENT", "HEALTHY-B", "HEALTHY-A"), ordered);
    }

    private static CompanyResearchSummarySnapshot currentSnapshot() {
        return new CompanyResearchSummarySnapshot(
                "ANY", LocalDate.parse("2026-06-30"), 100_000_000_000.0,
                12.0, 25.0, 4.0,
                80, 75, 85, 77, 83,
                82, "매수 우호", 84, 25,
                "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-25"),
                "10-Q", 0, List.of(),
                80, 76, 20, 85, "CONVICTION", Instant.parse("2026-08-07T02:00:00Z")
        );
    }

    private static final class CapturingRepository implements CompanyResearchSummaryRepository {
        private final CompanyResearchSummarySnapshot existing;
        private final AtomicReference<CompanyResearchSummarySnapshot> saved = new AtomicReference<>();

        private CapturingRepository(CompanyResearchSummarySnapshot existing) {
            this.existing = existing;
        }

        @Override public Optional<CompanyResearchSummarySnapshot> find(String normalizedTicker) {
            return Optional.of(existing);
        }

        @Override public Map<String, CompanyResearchSummarySnapshot> findAll() {
            return Map.of(existing.ticker(), existing);
        }

        @Override public void save(CompanyResearchSummarySnapshot snapshot) {
            saved.set(snapshot);
        }
    }
}
