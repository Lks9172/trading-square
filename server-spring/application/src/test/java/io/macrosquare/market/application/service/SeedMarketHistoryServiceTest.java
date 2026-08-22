package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketHistorySeedSeries;
import io.macrosquare.market.application.port.out.LoadMarketHistorySeedPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedMarketHistoryServiceTest {

    @Test
    void backfillsMissingDatesWithoutReplacingSpringOwnedObservationsAndIsIdempotent() {
        var fred = new MarketHistorySeedSeries(MarketDataSource.FRED, "DGS10", "DGS10");
        var yahoo = new MarketHistorySeedSeries(MarketDataSource.YAHOO, "NASDAQ", "^IXIC");
        var source = new StubSeedPort(Map.of(
                fred, List.of(
                        point(fred, "2026-07-17", 99.0),
                        point(fred, "2026-07-18", 4.2),
                        point(fred, "2026-07-19", 4.3)),
                yahoo, List.of(point(yahoo, "2026-07-18", 22000))
        ));
        var repository = new MemoryRepository();
        repository.save(List.of(point(fred, "2026-07-17", 4.1)));
        var service = new SeedMarketHistoryService(
                source, repository, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));

        var first = service.seedMissingHistory();
        var second = service.seedMissingHistory();

        assertEquals(2, first.availableSeries());
        assertEquals(2, first.seededSeries());
        assertEquals(0, first.skippedExistingSeries());
        assertEquals(3, first.persistedPoints());
        assertTrue(first.failures().isEmpty());
        assertEquals(0, second.seededSeries());
        assertEquals(2, second.skippedExistingSeries());
        assertEquals(4.1, repository.loadHistory(MarketDataSource.FRED, "DGS10").stream()
                .filter(item -> item.observationDate().equals(LocalDate.parse("2026-07-17")))
                .findFirst()
                .orElseThrow()
                .value());
    }

    @Test
    void partialPersistenceIsReportedAsAFailedSeedInsteadOfSuccess() {
        var series = new MarketHistorySeedSeries(MarketDataSource.YAHOO, "SP500", "^GSPC");
        var source = new StubSeedPort(Map.of(
                series, List.of(point(series, "2026-07-20", 5000))));
        MarketObservationRepository repository = new MarketObservationRepository() {
            @Override public int save(List<MarketObservation> observations) { return 0; }
            @Override public List<MarketObservation> loadLatest(MarketDataSource source) { return List.of(); }
            @Override public List<MarketObservation> loadHistory(MarketDataSource source, String key) { return List.of(); }
        };
        var service = new SeedMarketHistoryService(
                source, repository, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC));

        var report = service.seedMissingHistory();

        assertFalse(report.successful());
        assertEquals(0, report.seededSeries());
        assertEquals(1, report.failures().size());
    }

    private static MarketObservation point(MarketHistorySeedSeries series, String date, double value) {
        return new MarketObservation(series.key(), series.providerCode(), value, LocalDate.parse(date), series.source());
    }

    private record StubSeedPort(Map<MarketHistorySeedSeries, List<MarketObservation>> values)
            implements LoadMarketHistorySeedPort {
        @Override public List<MarketHistorySeedSeries> listAvailableSeries() { return List.copyOf(values.keySet()); }
        @Override public List<MarketObservation> load(MarketHistorySeedSeries series) { return values.get(series); }
    }

    private static final class MemoryRepository implements MarketObservationRepository {
        private final Map<MarketDataSource, Map<String, List<MarketObservation>>> values =
                new EnumMap<>(MarketDataSource.class);

        @Override public int save(List<MarketObservation> observations) {
            for (var item : observations) {
                var byKey = values.computeIfAbsent(item.source(), ignored -> new HashMap<>());
                var byDate = new HashMap<LocalDate, MarketObservation>();
                byKey.getOrDefault(item.key(), List.of()).forEach(point ->
                        byDate.put(point.observationDate(), point));
                byDate.put(item.observationDate(), item);
                byKey.put(item.key(), byDate.values().stream()
                        .sorted(java.util.Comparator.comparing(MarketObservation::observationDate))
                        .toList());
            }
            return observations.size();
        }

        @Override public List<MarketObservation> loadLatest(MarketDataSource source) { return List.of(); }

        @Override public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
            return values.getOrDefault(source, Map.of()).getOrDefault(key, List.of());
        }
    }
}
