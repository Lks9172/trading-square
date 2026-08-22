package io.macrosquare.company.adapter.in.scheduling;

import io.macrosquare.company.application.port.in.RecordCompanyAnalystHistoryUseCase;
import io.macrosquare.shared.adapter.in.scheduling.ScheduledTaskExecutionException;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.application.port.out.ScheduledTaskNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Feature-flagged scheduler for the application-owned analyst-history store. */
public final class CompanyAnalystHistoryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompanyAnalystHistoryScheduler.class);
    private static final String SCHEDULER = "companyAnalystHistoryTaskScheduler";

    private final RecordCompanyAnalystHistoryUseCase useCase;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public CompanyAnalystHistoryScheduler(RecordCompanyAnalystHistoryUseCase useCase) {
        this(useCase, ExclusiveTaskExecution.local());
    }

    public CompanyAnalystHistoryScheduler(
            RecordCompanyAnalystHistoryUseCase useCase,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.useCase = Objects.requireNonNull(useCase);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.company-analyst-history.startup-delay:30s}",
            scheduler = SCHEDULER
    )
    public void recordAfterStartup() {
        run("startup-seed");
    }

    @Scheduled(
            cron = "${macrosquare.company-analyst-history.weekday-cron:0 15 * * * 1-5}",
            zone = "${macrosquare.company-analyst-history.zone:Asia/Seoul}",
            scheduler = SCHEDULER
    )
    public void recordWeekday() {
        run("weekday-1h");
    }

    @Scheduled(
            cron = "${macrosquare.company-analyst-history.weekend-cron:0 15 * * * 0,6}",
            zone = "${macrosquare.company-analyst-history.zone:Asia/Seoul}",
            scheduler = SCHEDULER
    )
    public void recordWeekend() {
        run("weekend-1h");
    }

    private void run(String trigger) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("Analyst history run skipped because another run is active (trigger={})", trigger);
            return;
        }
        var invocationStartedAt = System.nanoTime();
        try {
            var executed = exclusiveTasks.execute(ScheduledTaskNames.COMPANY_PROVIDER_HEAVY, () -> {
                var startedAt = System.nanoTime();
                LOGGER.info("Analyst history run started (trigger={})", trigger);
                var report = useCase.recordDailyHistory();
                if (report.successful()) {
                    LOGGER.info(
                            "Analyst history completed (trigger={}, date={}, attempted={}, written={}, seeded={}, durationMs={})",
                            trigger,
                            report.observationDate(),
                            report.attempted(),
                            report.written(),
                            report.seededFromLegacy(),
                            elapsedMillis(startedAt)
                    );
                } else {
                    LOGGER.warn(
                            "Analyst history completed with failures (trigger={}, date={}, attempted={}, written={}, failureCount={}, failures={}, durationMs={})",
                            trigger,
                            report.observationDate(),
                            report.attempted(),
                            report.written(),
                            report.failures().size(),
                            report.failures(),
                            elapsedMillis(startedAt)
                    );
                    throw new ScheduledTaskExecutionException(
                            "company-analyst-history",
                            "partial failures=" + report.failures().size()
                    );
                }
            });
            if (!executed) {
                LOGGER.info("Analyst history deferred because another provider-heavy company job owns the slot (trigger={})",
                        trigger);
            }
        } catch (RuntimeException error) {
            LOGGER.error(
                    "Analyst history run failed (trigger={}, errorType={}, durationMs={})",
                    trigger,
                    error.getClass().getSimpleName(),
                    elapsedMillis(invocationStartedAt)
            );
            throw error;
        } finally {
            running.set(false);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
