package io.macrosquare.market.adapter.in.scheduling;

import io.macrosquare.market.application.port.in.RefreshMarketObservationsUseCase;
import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.model.MarketCollectionStatus;
import io.macrosquare.market.application.port.out.MarketCollectionStatusRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.shared.adapter.in.scheduling.ScheduledTaskExecutionException;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Feature-flagged collector scheduler writing only to the Spring-owned market store. */
public final class MarketObservationCollectionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketObservationCollectionScheduler.class);
    private static final String SCHEDULER = "marketObservationTaskScheduler";

    private final RefreshMarketObservationsUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final MarketCollectionStatusRepository collectionStatuses;
    private final Clock clock;
    private final EnumMap<MarketDataSource, AtomicBoolean> running = new EnumMap<>(MarketDataSource.class);

    public MarketObservationCollectionScheduler(RefreshMarketObservationsUseCase refresh) {
        this(refresh, ExclusiveTaskExecution.local(), MarketCollectionStatusRepository.none(), Clock.systemUTC());
    }

    public MarketObservationCollectionScheduler(
            RefreshMarketObservationsUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this(refresh, exclusiveTasks, MarketCollectionStatusRepository.none(), Clock.systemUTC());
    }

    public MarketObservationCollectionScheduler(
            RefreshMarketObservationsUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks,
            MarketCollectionStatusRepository collectionStatuses,
            Clock clock
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
        this.collectionStatuses = Objects.requireNonNull(collectionStatuses);
        this.clock = Objects.requireNonNull(clock);
        for (var source : MarketDataSource.values()) running.put(source, new AtomicBoolean());
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.fred-fixed-delay:6h}",
            scheduler = SCHEDULER
    )
    public void collectFred() {
        run(MarketDataSource.FRED, "scheduled");
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.yahoo-fixed-delay:15m}",
            scheduler = SCHEDULER
    )
    public void collectYahoo() {
        run(MarketDataSource.YAHOO, "scheduled");
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.fear-greed-fixed-delay:1h}",
            scheduler = SCHEDULER
    )
    public void collectFearGreed() {
        run(MarketDataSource.FEAR_GREED, "scheduled");
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.sentiment-fixed-delay:6h}",
            scheduler = SCHEDULER
    )
    public void collectSentiment() {
        run(MarketDataSource.SENTIMENT, "scheduled");
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.stablecoin-fixed-delay:6h}",
            scheduler = SCHEDULER
    )
    public void collectStablecoin() {
        run(MarketDataSource.STABLECOIN, "scheduled");
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.krx-fixed-delay:30m}",
            scheduler = SCHEDULER
    )
    public void collectKrx() {
        run(MarketDataSource.KRX, "scheduled");
    }

    private void run(MarketDataSource source, String trigger) {
        var guard = running.get(source);
        if (!guard.compareAndSet(false, true)) {
            LOGGER.warn("Spring market collection skipped because another run is active (source={}, trigger={})",
                    source, trigger);
            return;
        }
        var attemptedAt = clock.instant();
        var statusRecorded = new AtomicBoolean();
        try {
            var taskName = "market:observation:" + source.name().toLowerCase(java.util.Locale.ROOT);
            var executed = exclusiveTasks.execute(taskName, () -> {
                var report = refresh.refresh(source);
                record(MarketCollectionStatus.from(report));
                statusRecorded.set(true);
                if (report.successful()) {
                    LOGGER.info(
                            "Spring market collection completed (source={}, collected={}, persisted={}, durationMs={})",
                            source,
                            report.collected(),
                            report.persisted(),
                            report.completedAt().toEpochMilli() - report.startedAt().toEpochMilli()
                    );
                } else if (report.providerPolicyLimitedOnly()) {
                    LOGGER.info(
                            "Spring market collection completed with provider-policy exclusions "
                                    + "(source={}, collected={}, persisted={}, exclusions={})",
                            source,
                            report.collected(),
                            report.persisted(),
                            report.failures().stream().map(MarketCollectionBatch.Failure::key).toList()
                    );
                } else {
                    LOGGER.warn(
                            "Spring market collection completed with gaps (source={}, collected={}, persisted={}, failureCount={}, failures={})",
                            source,
                            report.collected(),
                            report.persisted(),
                            report.failures().size(),
                            report.failures().stream()
                                    .map(item -> item.key() + "=" + item.reason())
                                    .toList()
                    );
                    if (report.collected() == 0 || report.persisted() == 0) {
                        throw new ScheduledTaskExecutionException(
                                "market-observation-" + source.name().toLowerCase(java.util.Locale.ROOT),
                                "no usable observations; failures=" + report.failures().size()
                        );
                    }
                }
            });
            if (!executed) {
                LOGGER.info("Spring market collection skipped because another instance owns the task (source={}, trigger={})",
                        source, trigger);
            }
        } catch (RuntimeException error) {
            if (!statusRecorded.get()) {
                record(MarketCollectionStatus.failed(source, attemptedAt, clock.instant(), error));
            }
            LOGGER.error("Spring market collection failed (source={}, errorType={})",
                    source, error.getClass().getSimpleName());
            throw error;
        } finally {
            guard.set(false);
        }
    }

    private void record(MarketCollectionStatus status) {
        try {
            collectionStatuses.save(status);
        } catch (RuntimeException error) {
            // Diagnostics must not invalidate observations that were already persisted.
            LOGGER.warn("Unable to persist market collection status (source={}, state={}, errorType={})",
                    status.source(), status.state(), error.getClass().getSimpleName(), error);
        }
    }
}
