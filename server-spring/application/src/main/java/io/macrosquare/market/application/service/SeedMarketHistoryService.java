package io.macrosquare.market.application.service;

import io.macrosquare.market.application.port.in.MarketHistorySeedReport;
import io.macrosquare.market.application.port.in.SeedMarketHistoryUseCase;
import io.macrosquare.market.application.port.out.LoadMarketHistorySeedPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

/**
 * Idempotently backfills missing historical dates into the Spring-owned repository.
 * Existing Spring observations always win, making restarts and rollback/retry safe.
 */
public final class SeedMarketHistoryService implements SeedMarketHistoryUseCase {

    private final LoadMarketHistorySeedPort seedPort;
    private final MarketObservationRepository repository;
    private final Clock clock;

    public SeedMarketHistoryService(
            LoadMarketHistorySeedPort seedPort,
            MarketObservationRepository repository,
            Clock clock
    ) {
        this.seedPort = Objects.requireNonNull(seedPort);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MarketHistorySeedReport seedMissingHistory() {
        var startedAt = clock.instant();
        var available = seedPort.listAvailableSeries();
        var seeded = 0;
        var skipped = 0;
        var persisted = 0;
        var failures = new ArrayList<String>();
        for (var series : available) {
            try {
                var observations = seedPort.load(series);
                if (observations.isEmpty()) {
                    failures.add(series.source() + ":" + series.key() + ":empty");
                    continue;
                }
                var existingDates = new HashSet<>(repository.loadHistory(series.source(), series.key()).stream()
                        .map(item -> item.observationDate())
                        .toList());
                var missing = observations.stream()
                        .filter(item -> !existingDates.contains(item.observationDate()))
                        .toList();
                if (missing.isEmpty()) {
                    skipped++;
                    continue;
                }
                var written = repository.save(missing);
                if (written != missing.size()) {
                    failures.add(series.source() + ":" + series.key()
                            + ":persistence-count-mismatch:expected=" + missing.size()
                            + ":actual=" + written);
                    continue;
                }
                persisted += written;
                seeded++;
            } catch (RuntimeException error) {
                failures.add(series.source() + ":" + series.key() + ":" + error.getClass().getSimpleName());
            }
        }
        return new MarketHistorySeedReport(
                startedAt, clock.instant(), available.size(), seeded, skipped, persisted, failures);
    }
}
