package io.macrosquare.company.application.service;

import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistorySeedPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistoryStorePort;
import io.macrosquare.company.application.port.out.SaveCompanyAnalystHistoryPort;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.service.CompanyAnalystHistoryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordCompanyAnalystHistoryServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-19T15:30:00Z"),
            ZoneOffset.ofHours(9)
    );

    @Test
    void seedsMissingStoreHistoryFromCutoverEvidenceAndUsesTheUtcObservationDate() {
        var saved = new HashMap<String, List<CompanyAnalystHistoryPoint>>();
        var service = service(
                ticker -> new CompanyAnalystConsensus(1.1, 20.0),
                ticker -> List.of(point("2026-07-18", 1.0, 19.0)),
                ticker -> Optional.empty(),
                (ticker, history, updatedAt) -> saved.put(ticker, history),
                List.of("nvda", "NVDA"),
                365
        );

        var report = service.recordDailyHistory();

        assertTrue(report.successful());
        assertEquals(LocalDate.parse("2026-07-19"), report.observationDate());
        assertEquals(1, report.attempted());
        assertEquals(1, report.written());
        assertEquals(1, report.seededFromLegacy());
        assertEquals(List.of(
                point("2026-07-18", 1.0, 19.0),
                point("2026-07-19", 1.1, 20.0)
        ), saved.get("NVDA"));
    }

    @Test
    void continuesFromStoreWithoutReadingSeedAgain() {
        var seedReads = new AtomicInteger();
        var saved = new HashMap<String, List<CompanyAnalystHistoryPoint>>();
        var service = service(
                ticker -> new CompanyAnalystConsensus(1.2, 22.0),
                ticker -> {
                    seedReads.incrementAndGet();
                    return List.of();
                },
                ticker -> Optional.of(List.of(point("2026-07-18", 1.0, 20.0))),
                (ticker, history, updatedAt) -> saved.put(ticker, history),
                List.of("NVDA"),
                365
        );

        var report = service.recordDailyHistory();

        assertTrue(report.successful());
        assertEquals(0, seedReads.get());
        assertEquals(0, report.seededFromLegacy());
        assertEquals(2, saved.get("NVDA").size());
    }

    @Test
    void rejectsProviderWideEmptyConsensusInsteadOfPoisoningRevisionHistory() {
        var saved = new HashMap<String, List<CompanyAnalystHistoryPoint>>();
        var service = service(
                ticker -> new CompanyAnalystConsensus(null, null),
                ticker -> List.of(),
                ticker -> Optional.empty(),
                (ticker, history, updatedAt) -> saved.put(ticker, history),
                List.of("NVDA"),
                365
        );

        var report = service.recordDailyHistory();

        assertFalse(report.successful());
        assertEquals(0, report.written());
        assertEquals("NVDA", report.failures().getFirst().ticker());
        assertEquals("IllegalArgumentException", report.failures().getFirst().reason());
        assertFalse(saved.containsKey("NVDA"));
    }

    @Test
    void isolatesTickerFailuresAndCompletesTheRemainingBatch() {
        var saved = new HashMap<String, List<CompanyAnalystHistoryPoint>>();
        var service = service(
                ticker -> {
                    if (ticker.equals("AAPL")) throw new IllegalStateException("simulated");
                    return new CompanyAnalystConsensus(1.1, 20.0);
                },
                ticker -> List.of(),
                ticker -> Optional.empty(),
                (ticker, history, updatedAt) -> saved.put(ticker, history),
                List.of("AAPL", "NVDA"),
                365
        );

        var report = service.recordDailyHistory();

        assertFalse(report.successful());
        assertEquals(2, report.attempted());
        assertEquals(1, report.written());
        assertEquals("AAPL", report.failures().getFirst().ticker());
        assertEquals("IllegalStateException", report.failures().getFirst().reason());
        assertTrue(saved.containsKey("NVDA"));
    }

    @Test
    void mergesTheConfiguredCoreTickersWithTheCurrentResearchUniverse() {
        var saved = new HashMap<String, List<CompanyAnalystHistoryPoint>>();
        var service = new RecordCompanyAnalystHistoryService(
                ticker -> new CompanyAnalystConsensus(1.0, 10.0),
                ticker -> List.of(),
                ticker -> Optional.empty(),
                (ticker, history, updatedAt) -> saved.put(ticker, history),
                () -> List.of("nem", "NVDA", "brk.b"),
                new CompanyAnalystHistoryPolicy(),
                CLOCK,
                List.of("NVDA", "AAPL"),
                365
        );

        var report = service.recordDailyHistory();

        assertEquals(4, report.attempted());
        assertEquals(List.of("AAPL", "BRK-B", "NEM", "NVDA"), saved.keySet().stream().sorted().toList());
    }

    private static RecordCompanyAnalystHistoryService service(
            io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort consensus,
            LoadCompanyAnalystHistorySeedPort seed,
            LoadCompanyAnalystHistoryStorePort store,
            SaveCompanyAnalystHistoryPort save,
            List<String> tickers,
            int retention
    ) {
        return new RecordCompanyAnalystHistoryService(
                consensus,
                seed,
                store,
                save,
                new CompanyAnalystHistoryPolicy(),
                CLOCK,
                tickers,
                retention
        );
    }

    private static CompanyAnalystHistoryPoint point(String date, Double score, Double upside) {
        return new CompanyAnalystHistoryPoint(LocalDate.parse(date), score, upside);
    }
}
