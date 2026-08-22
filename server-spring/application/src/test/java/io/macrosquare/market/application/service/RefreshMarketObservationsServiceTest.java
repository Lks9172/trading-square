package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefreshMarketObservationsServiceTest {

    @Test
    void persistsOnlySuccessfulObservationsAndRetainsFailureEvidence() {
        var observation = new MarketObservation(
                "DGS10", "DGS10", 4.2, LocalDate.parse("2026-07-20"), MarketDataSource.FRED);
        CollectMarketObservationsPort collector = new CollectMarketObservationsPort() {
            @Override
            public MarketDataSource source() {
                return MarketDataSource.FRED;
            }

            @Override
            public MarketCollectionBatch collect() {
                return new MarketCollectionBatch(
                        source(), Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of(observation),
                        List.of(new MarketCollectionBatch.Failure("VIXCLS", "HTTP 429")));
            }
        };
        var saves = new AtomicInteger();
        MarketObservationRepository repository = new MarketObservationRepository() {
            @Override
            public int save(List<MarketObservation> observations) {
                saves.incrementAndGet();
                return observations.size();
            }

            @Override
            public List<MarketObservation> loadLatest(MarketDataSource source) {
                return List.of();
            }

            @Override
            public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
                return List.of();
            }
        };
        var service = new RefreshMarketObservationsService(List.of(collector), repository);

        var report = service.refresh(MarketDataSource.FRED);

        assertEquals(1, report.collected());
        assertEquals(1, report.persisted());
        assertEquals("VIXCLS", report.failures().getFirst().key());
        assertEquals(1, saves.get());
    }

    @Test
    void rejectsDuplicateSourceCollectors() {
        CollectMarketObservationsPort collector = new EmptyCollector();
        MarketObservationRepository repository = new EmptyRepository();
        assertThrows(IllegalArgumentException.class,
                () -> new RefreshMarketObservationsService(List.of(collector, collector), repository));
    }

    private static final class EmptyCollector implements CollectMarketObservationsPort {
        @Override
        public MarketDataSource source() {
            return MarketDataSource.FRED;
        }

        @Override
        public MarketCollectionBatch collect() {
            return new MarketCollectionBatch(source(), Instant.EPOCH, Instant.EPOCH, List.of(), List.of());
        }
    }

    private static final class EmptyRepository implements MarketObservationRepository {
        @Override
        public int save(List<MarketObservation> observations) {
            return observations.size();
        }

        @Override
        public List<MarketObservation> loadLatest(MarketDataSource source) {
            return List.of();
        }

        @Override
        public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
            return List.of();
        }
    }
}
