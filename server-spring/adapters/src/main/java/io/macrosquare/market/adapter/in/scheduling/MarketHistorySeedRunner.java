package io.macrosquare.market.adapter.in.scheduling;

import io.macrosquare.market.application.port.in.SeedMarketHistoryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

import java.util.Objects;

/** Runs the idempotent read-only migration seed before regular collectors begin. */
public final class MarketHistorySeedRunner implements ApplicationRunner, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketHistorySeedRunner.class);
    private final SeedMarketHistoryUseCase seed;

    public MarketHistorySeedRunner(SeedMarketHistoryUseCase seed) {
        this.seed = Objects.requireNonNull(seed);
    }

    @Override
    public void run(ApplicationArguments args) {
        var report = seed.seedMissingHistory();
        if (report.successful()) {
            LOGGER.info("Spring market history seed completed (available={}, seeded={}, skipped={}, points={})",
                    report.availableSeries(), report.seededSeries(), report.skippedExistingSeries(),
                    report.persistedPoints());
        } else {
            LOGGER.warn("Spring market history seed completed with gaps (available={}, seeded={}, skipped={}, points={}, failures={})",
                    report.availableSeries(), report.seededSeries(), report.skippedExistingSeries(),
                    report.persistedPoints(), report.failures());
        }
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
