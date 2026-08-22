package io.macrosquare.market.adapter.in.scheduling;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.model.MarketCollectionReport;
import io.macrosquare.market.adapter.out.persistence.InMemoryMarketCollectionStatusRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.shared.adapter.in.scheduling.ScheduledTaskExecutionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketObservationCollectionSchedulerTest {

    @Test
    void partialSuccessIsDegradedButDoesNotFailTheScheduledTask() {
        var statuses = new InMemoryMarketCollectionStatusRepository();
        var scheduler = new MarketObservationCollectionScheduler(
                source -> report(source, 3, 3, 2),
                io.macrosquare.shared.application.port.out.ExclusiveTaskExecution.local(),
                statuses,
                Clock.fixed(Instant.parse("2026-08-05T12:00:10Z"), ZoneOffset.UTC));

        assertDoesNotThrow(scheduler::collectSentiment);
        var status = statuses.loadLatest().get(MarketDataSource.SENTIMENT);
        assertEquals(io.macrosquare.market.application.model.MarketCollectionStatus.State.DEGRADED,
                status.state());
        assertEquals(List.of("KEY_0", "KEY_1"), status.failureKeys());
    }

    @Test
    void zeroUsableObservationsStillFailsTheScheduledTask() {
        var statuses = new InMemoryMarketCollectionStatusRepository();
        var scheduler = new MarketObservationCollectionScheduler(
                source -> report(source, 0, 0, 2),
                io.macrosquare.shared.application.port.out.ExclusiveTaskExecution.local(),
                statuses,
                Clock.fixed(Instant.parse("2026-08-05T12:00:10Z"), ZoneOffset.UTC));

        assertThrows(ScheduledTaskExecutionException.class, scheduler::collectSentiment);
        assertEquals(io.macrosquare.market.application.model.MarketCollectionStatus.State.FAILED,
                statuses.loadLatest().get(MarketDataSource.SENTIMENT).state());
    }

    @Test
    void providerPolicyExclusionIsPersistedAsLimitedWithoutFailingTheTask() {
        var statuses = new InMemoryMarketCollectionStatusRepository();
        var at = Instant.parse("2026-08-05T12:00:00Z");
        var scheduler = new MarketObservationCollectionScheduler(
                source -> new MarketCollectionReport(
                        source, at, at.plusSeconds(1), 2, 2,
                        List.of(new MarketCollectionBatch.Failure(
                                "NAAIM_EXPOSURE",
                                "Provider data is delayed beyond decision freshness",
                                MarketCollectionBatch.FailureKind.PROVIDER_POLICY_UNAVAILABLE))),
                io.macrosquare.shared.application.port.out.ExclusiveTaskExecution.local(),
                statuses,
                Clock.fixed(at.plusSeconds(10), ZoneOffset.UTC));

        assertDoesNotThrow(scheduler::collectSentiment);

        var status = statuses.loadLatest().get(MarketDataSource.SENTIMENT);
        assertEquals(io.macrosquare.market.application.model.MarketCollectionStatus.State.DEGRADED,
                status.state());
        assertEquals("PROVIDER_POLICY_UNAVAILABLE", status.failureType());
    }

    @Test
    void collectedButNotPersistedObservationsFailClosedAndAreDetected() {
        var statuses = new InMemoryMarketCollectionStatusRepository();
        var scheduler = new MarketObservationCollectionScheduler(
                source -> report(source, 3, 0, 0),
                io.macrosquare.shared.application.port.out.ExclusiveTaskExecution.local(),
                statuses,
                Clock.fixed(Instant.parse("2026-08-05T12:00:10Z"), ZoneOffset.UTC));

        assertThrows(ScheduledTaskExecutionException.class, scheduler::collectYahoo);
        var status = statuses.loadLatest().get(MarketDataSource.YAHOO);
        assertEquals(io.macrosquare.market.application.model.MarketCollectionStatus.State.FAILED,
                status.state());
        assertEquals(3, status.collected());
        assertEquals(0, status.persisted());
        assertEquals("PERSISTENCE_COUNT_MISMATCH", status.failureType());
    }

    private static MarketCollectionReport report(
            MarketDataSource source,
            int collected,
            int persisted,
            int failures
    ) {
        var at = Instant.parse("2026-08-05T12:00:00Z");
        var gaps = java.util.stream.IntStream.range(0, failures)
                .mapToObj(index -> new MarketCollectionBatch.Failure("KEY_" + index, "unavailable"))
                .toList();
        return new MarketCollectionReport(source, at, at.plusSeconds(1), collected, persisted, gaps);
    }
}
