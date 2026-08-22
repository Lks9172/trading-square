package io.macrosquare.market.adapter.in.scheduling;

import io.macrosquare.market.application.port.in.RefreshSectorTotalReturnHistoryUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bootstraps the long total-return history before snapshots and then rolls it forward. */
public final class SectorTotalReturnHistoryScheduler implements ApplicationRunner, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(SectorTotalReturnHistoryScheduler.class);
    private static final String TASK = "market:sector-total-return-history";
    private static final String SCHEDULER = "marketObservationTaskScheduler";

    private final RefreshSectorTotalReturnHistoryUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public SectorTotalReturnHistoryScheduler(
            RefreshSectorTotalReturnHistoryUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Override
    public void run(ApplicationArguments args) {
        execute("startup");
    }

    @Scheduled(
            initialDelayString = "${macrosquare.market-collection.startup-delay:45s}",
            fixedDelayString = "${macrosquare.market-collection.sector-total-return-fixed-delay:6h}",
            scheduler = SCHEDULER
    )
    public void refreshScheduled() {
        execute("scheduled");
    }

    private void execute(String trigger) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.info("Sector total-return refresh skipped because another local run is active (trigger={})", trigger);
            return;
        }
        try {
            var executed = exclusiveTasks.execute(TASK, () -> {
                var report = refresh.refresh();
                if (report.successful()) {
                    LOGGER.info(
                            "Sector total-return refresh completed (trigger={}, fullBackfill={}, collected={}, persisted={}, durationMs={})",
                            trigger, report.fullBackfill(), report.collected(), report.persisted(),
                            report.completedAt().toEpochMilli() - report.startedAt().toEpochMilli());
                } else {
                    LOGGER.warn(
                            "Sector total-return refresh completed with gaps (trigger={}, fullBackfill={}, collected={}, persisted={}, failures={})",
                            trigger, report.fullBackfill(), report.collected(), report.persisted(),
                            report.failures().stream().map(value -> value.key() + "=" + value.reason()).toList());
                }
            });
            if (!executed) {
                LOGGER.info("Sector total-return refresh skipped because another instance owns the task (trigger={})", trigger);
            }
        } catch (RuntimeException error) {
            // Existing price-relative signals remain available as an explicit fallback.
            LOGGER.error("Sector total-return refresh failed (trigger={}, errorType={})",
                    trigger, error.getClass().getSimpleName(), error);
        } finally {
            running.set(false);
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
