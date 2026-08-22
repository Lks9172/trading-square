package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectSectorTotalReturnHistoryPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshSectorTotalReturnHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void requestsFullBackfillWhenAnyRequiredSeriesIsMissing() {
        var repository = new Repository();
        var collector = new Collector();
        var service = new RefreshSectorTotalReturnHistoryService(
                collector, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.refresh();

        assertTrue(report.fullBackfill());
        assertTrue(collector.full);
    }

    @Test
    void requestsOnlyRecentHistoryAfterEverySeriesHasTheLongWalkForwardCoverage() {
        var repository = new Repository();
        for (var key : RefreshSectorTotalReturnHistoryService.REQUIRED_SERIES) {
            repository.values.put(key, history(key, 2_000));
        }
        var collector = new Collector();
        var service = new RefreshSectorTotalReturnHistoryService(
                collector, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.refresh();

        assertFalse(report.fullBackfill());
        assertFalse(collector.full);
    }

    @Test
    void rebasesTheFullHistoryWhenAnAdjustedCloseOverlapChanges() {
        var repository = new Repository();
        for (var key : RefreshSectorTotalReturnHistoryService.REQUIRED_SERIES) {
            repository.values.put(key, history(key, 2_000));
        }
        var collector = new Collector();
        collector.value = 99;
        var service = new RefreshSectorTotalReturnHistoryService(
                collector, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.refresh();

        assertTrue(report.fullBackfill());
        assertTrue(collector.full);
        assertEquals(2, collector.calls);
    }

    @Test
    void neverPersistsAPartialStandardSectorCrossSection() {
        var repository = new Repository();
        var collector = new Collector();
        collector.observations = RefreshSectorTotalReturnHistoryService.STANDARD_ROTATION_SERIES.stream()
                .filter(key -> !key.equals("XLF_TR"))
                .flatMap(key -> history(key, 2_000).stream())
                .toList();
        var service = new RefreshSectorTotalReturnHistoryService(
                collector, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.refresh();

        assertEquals(0, report.persisted());
        assertEquals(0, repository.saved);
        assertTrue(report.failures().stream().anyMatch(failure -> failure.key().equals("XLF_TR")));
    }

    @Test
    void persistsACompleteStandardGroupEvenWhenAnOptionalThemeFails() {
        var repository = new Repository();
        var collector = new Collector();
        collector.observations = RefreshSectorTotalReturnHistoryService.STANDARD_ROTATION_SERIES.stream()
                .flatMap(key -> history(key, 2_000).stream())
                .toList();
        collector.failures = List.of(new MarketCollectionBatch.Failure("SOXX_TR", "HTTP 502"));
        var service = new RefreshSectorTotalReturnHistoryService(
                collector, repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.refresh();

        assertEquals(collector.observations.size(), report.persisted());
        assertEquals(collector.observations.size(), repository.saved);
        assertTrue(report.failures().stream().anyMatch(failure -> failure.key().equals("SOXX_TR")));
    }

    private static List<MarketObservation> history(String key, int count) {
        var result = new ArrayList<MarketObservation>(count);
        var latest = LocalDate.parse("2026-08-07");
        for (var index = count - 1; index >= 0; index--) {
            result.add(new MarketObservation(
                    key, key, 100 + index, latest.minusDays(index), MarketDataSource.YAHOO));
        }
        return List.copyOf(result);
    }

    private static final class Collector implements CollectSectorTotalReturnHistoryPort {
        private boolean full;
        private int calls;
        private double value = 100;
        private List<MarketObservation> observations;
        private List<MarketCollectionBatch.Failure> failures = List.of();

        @Override
        public MarketCollectionBatch collect(HistoryWindow window) {
            calls++;
            full = window == HistoryWindow.FULL;
            var values = observations == null ? List.of(new MarketObservation(
                    "SPY_TR", "SPY", value, LocalDate.parse("2026-08-07"), MarketDataSource.YAHOO))
                    : observations;
            return new MarketCollectionBatch(MarketDataSource.YAHOO, NOW, NOW, values, failures);
        }
    }

    private static final class Repository implements MarketObservationRepository {
        private final HashMap<String, List<MarketObservation>> values = new HashMap<>();
        private int saved;

        @Override
        public int save(List<MarketObservation> observations) {
            saved += observations.size();
            return observations.size();
        }

        @Override
        public List<MarketObservation> loadLatest(MarketDataSource source) {
            return List.of();
        }

        @Override
        public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
            return values.getOrDefault(key, List.of());
        }
    }
}
