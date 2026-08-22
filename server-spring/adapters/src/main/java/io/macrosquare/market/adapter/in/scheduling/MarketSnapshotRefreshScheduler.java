package io.macrosquare.market.adapter.in.scheduling;

import io.macrosquare.market.application.port.in.RefreshMarketSnapshotUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds once during startup and subsequently after collectors on a bounded fixed delay. */
public final class MarketSnapshotRefreshScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketSnapshotRefreshScheduler.class);
    private final RefreshMarketSnapshotUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public MarketSnapshotRefreshScheduler(RefreshMarketSnapshotUseCase refresh) {
        this(refresh, ExclusiveTaskExecution.local());
    }

    public MarketSnapshotRefreshScheduler(
            RefreshMarketSnapshotUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.snapshot-startup-delay:75s}",
            fixedDelayString = "${macrosquare.market-collection.snapshot-fixed-delay:5m}",
            scheduler = "marketObservationTaskScheduler"
    )
    public void scheduled() {
        execute("scheduled");
    }

    private void execute(String trigger) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("Spring market snapshot refresh skipped because another run is active ({})", trigger);
            return;
        }
        try {
            var executed = exclusiveTasks.execute("market:snapshot-refresh", () -> {
                var report = refresh.refresh();
                LOGGER.info("Spring market snapshot refreshed (trigger={}, raw={}, derived={}, coreDerived={}, regime={}, score={}, durationMs={})",
                        trigger, report.rawCount(), report.derivedCount(), report.coreDerivedCount(), report.regime(),
                        report.regimeScore(), report.completedAt().toEpochMilli() - report.startedAt().toEpochMilli());
            });
            if (!executed) {
                LOGGER.info("Spring market snapshot refresh skipped because another instance owns the task ({})",
                        trigger);
            }
        } catch (RuntimeException error) {
            LOGGER.error("Spring market snapshot refresh failed (trigger={}, errorType={})",
                    trigger, error.getClass().getSimpleName(), error);
            throw error;
        } finally {
            running.set(false);
        }
    }

}
