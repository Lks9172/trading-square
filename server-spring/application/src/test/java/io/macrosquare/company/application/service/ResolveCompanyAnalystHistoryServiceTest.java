package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Mode.DUAL_COMPARE;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Mode.SEED_ONLY;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Mode.STORE_PREFERRED;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Source.SEED_FALLBACK;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Source.STORE;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.AVAILABLE;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.MISSING;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.NOT_EXPECTED;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.NOT_READ;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolveCompanyAnalystHistoryServiceTest {

    private static final List<CompanyAnalystHistoryPoint> SEED_HISTORY = List.of(
            point("2026-06-19", 1.0, 10.0),
            point("2026-07-19", 1.1, 12.0)
    );

    @Test
    void seedOnlyModeDoesNotTouchTheStore() {
        var storeReads = new AtomicInteger();
        var service = new ResolveCompanyAnalystHistoryService(
                ticker -> SEED_HISTORY,
                ticker -> {
                    storeReads.incrementAndGet();
                    return Optional.of(SEED_HISTORY);
                },
                SEED_ONLY,
                List.of("NVDA")
        );

        var result = service.resolve(" nvda ");

        assertEquals(CompanyAnalystHistoryRead.Source.SEED, result.selectedSource());
        assertEquals(NOT_READ, result.storeState());
        assertFalse(result.comparisonPerformed());
        assertTrue(result.matched());
        assertEquals(0, storeReads.get());
    }

    @Test
    void nonStoreTickerStaysOnSeedWithoutAFileProbe() {
        var storeReads = new AtomicInteger();
        var service = new ResolveCompanyAnalystHistoryService(
                ticker -> SEED_HISTORY,
                ticker -> {
                    storeReads.incrementAndGet();
                    return Optional.empty();
                },
                STORE_PREFERRED,
                List.of("NVDA")
        );

        var result = service.resolve("BRK.B");

        assertEquals("BRK-B", result.ticker());
        assertEquals(CompanyAnalystHistoryRead.Source.SEED, result.selectedSource());
        assertEquals(NOT_EXPECTED, result.storeState());
        assertTrue(result.matched());
        assertEquals(0, storeReads.get());
    }

    @Test
    void dualCompareKeepsSeedPrimaryAndReportsAnExactMatch() {
        var service = service(DUAL_COMPARE, Optional.of(SEED_HISTORY));

        var result = service.resolve("NVDA");

        assertEquals(CompanyAnalystHistoryRead.Source.SEED, result.selectedSource());
        assertEquals(AVAILABLE, result.seedState());
        assertEquals(AVAILABLE, result.storeState());
        assertTrue(result.comparisonPerformed());
        assertTrue(result.matched());
        assertEquals(2, result.seedPointCount());
        assertEquals(2, result.storePointCount());
        assertEquals(LocalDate.parse("2026-07-19"), result.storeLatestDate());
    }

    @Test
    void dualCompareKeepsServingSeedButFlagsAMissingStore() {
        var service = service(DUAL_COMPARE, Optional.empty());

        var result = service.resolve("NVDA");

        assertEquals(CompanyAnalystHistoryRead.Source.SEED, result.selectedSource());
        assertEquals(MISSING, result.storeState());
        assertFalse(result.comparisonPerformed());
        assertFalse(result.matched());
        assertEquals(List.of("analystHistory.storeMissing"), result.differences());
    }

    @Test
    void storePreferredSelectsTheVerifiedStoreHistory() {
        var service = service(STORE_PREFERRED, Optional.of(SEED_HISTORY));

        var result = service.resolve("NVDA");

        assertEquals(STORE, result.selectedSource());
        assertEquals(SEED_HISTORY, result.history());
        assertTrue(result.comparisonPerformed());
        assertTrue(result.matched());
    }

    @Test
    void storePreferredFallsBackToSeedWhenTheStoreIsMissing() {
        var service = service(STORE_PREFERRED, Optional.empty());

        var result = service.resolve("NVDA");

        assertEquals(SEED_FALLBACK, result.selectedSource());
        assertEquals(MISSING, result.storeState());
        assertEquals(SEED_HISTORY, result.history());
        assertFalse(result.matched());
    }

    @Test
    void storePreferredFallsBackToSeedWhenTheStoreIsUnreadable() {
        var service = new ResolveCompanyAnalystHistoryService(
                ticker -> SEED_HISTORY,
                ticker -> {
                    throw new IllegalStateException("filesystem details must not escape");
                },
                STORE_PREFERRED,
                List.of("NVDA")
        );

        var result = service.resolve("NVDA");

        assertEquals(SEED_FALLBACK, result.selectedSource());
        assertEquals(UNAVAILABLE, result.storeState());
        assertEquals(List.of("analystHistory.storeUnavailable"), result.differences());
    }

    @Test
    void storePreferredStillServesStoreWhenSeedComparisonIsUnavailable() {
        var service = new ResolveCompanyAnalystHistoryService(
                ticker -> {
                    throw new IllegalStateException("seed filesystem details");
                },
                ticker -> Optional.of(SEED_HISTORY),
                STORE_PREFERRED,
                List.of("NVDA")
        );

        var result = service.resolve("NVDA");

        assertEquals(STORE, result.selectedSource());
        assertEquals(UNAVAILABLE, result.seedState());
        assertEquals(AVAILABLE, result.storeState());
        assertEquals(List.of("analystHistory.seedUnavailable"), result.differences());
    }

    @Test
    void storePreferredReportsPointDriftWithoutSilentlySwitchingBack() {
        var shorterStore = List.of(point("2026-07-19", 1.1, 12.0));
        var service = service(STORE_PREFERRED, Optional.of(shorterStore));

        var result = service.resolve("NVDA");

        assertEquals(STORE, result.selectedSource());
        assertEquals(shorterStore, result.history());
        assertFalse(result.matched());
        assertEquals(
                List.of("analystHistory.pointCount", "analystHistory.points"),
                result.differences()
        );
    }

    private static ResolveCompanyAnalystHistoryService service(
            CompanyAnalystHistoryRead.Mode mode,
            Optional<List<CompanyAnalystHistoryPoint>> store
    ) {
        return new ResolveCompanyAnalystHistoryService(
                ticker -> SEED_HISTORY,
                ticker -> store,
                mode,
                List.of("NVDA")
        );
    }

    private static CompanyAnalystHistoryPoint point(String date, Double score, Double upside) {
        return new CompanyAnalystHistoryPoint(LocalDate.parse(date), score, upside);
    }
}
