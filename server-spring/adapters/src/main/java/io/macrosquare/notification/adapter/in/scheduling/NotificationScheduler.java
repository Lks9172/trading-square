package io.macrosquare.notification.adapter.in.scheduling;

import io.macrosquare.notification.application.port.in.NotificationOrchestrationUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxDispatchUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxMaintenanceUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.application.port.out.ScheduledTaskNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NotificationScheduler implements ApplicationRunner, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final Duration POST_STARTUP_RETRY_DELAY = Duration.ofMinutes(5);
    private static final int POST_STARTUP_MAX_ATTEMPTS = 6;
    private final NotificationOrchestrationUseCase notifications;
    private final NotificationOutboxDispatchUseCase outbox;
    private final NotificationOutboxMaintenanceUseCase outboxMaintenance;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Duration startupDelay;
    private final Duration postStartupRecalculationDelay;
    private final Duration outboxRetention;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean candidateScanActive = new AtomicBoolean();

    public NotificationScheduler(
            NotificationOrchestrationUseCase notifications,
            NotificationOutboxDispatchUseCase outbox,
            TaskScheduler scheduler,
            Clock clock,
            Duration startupDelay
    ) {
        this(notifications, outbox, cutoff -> 0, scheduler, clock, startupDelay,
                Duration.ofMinutes(20), Duration.ofDays(30), ExclusiveTaskExecution.local());
    }

    public NotificationScheduler(
            NotificationOrchestrationUseCase notifications,
            NotificationOutboxDispatchUseCase outbox,
            NotificationOutboxMaintenanceUseCase outboxMaintenance,
            TaskScheduler scheduler,
            Clock clock,
            Duration startupDelay,
            Duration postStartupRecalculationDelay,
            Duration outboxRetention,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.notifications = Objects.requireNonNull(notifications);
        this.outbox = Objects.requireNonNull(outbox);
        this.outboxMaintenance = Objects.requireNonNull(outboxMaintenance);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
        this.startupDelay = Objects.requireNonNull(startupDelay);
        this.postStartupRecalculationDelay = Objects.requireNonNull(postStartupRecalculationDelay);
        if (postStartupRecalculationDelay.isZero() || postStartupRecalculationDelay.isNegative()) {
            throw new IllegalArgumentException("postStartupRecalculationDelay must be positive");
        }
        this.outboxRetention = Objects.requireNonNull(outboxRetention);
        if (outboxRetention.isZero() || outboxRetention.isNegative()) {
            throw new IllegalArgumentException("outboxRetention must be positive");
        }
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Override
    public void run(ApplicationArguments args) {
        var startupAt = clock.instant().plus(startupDelay);
        scheduler.schedule(() -> {
            try {
                var executed = exclusiveTasks.execute("notification:startup", () -> {
                    var queued = notifications.dispatchStartup();
                    var delivered = outbox.dispatchPending();
                    LOGGER.info("Spring startup Telegram notification processed (queued={}, delivered={})",
                            queued, delivered);
                });
                if (!executed) {
                    LOGGER.info("Spring startup Telegram notification skipped because another instance owns the task");
                }
            } catch (RuntimeException error) {
                LOGGER.error("Spring startup Telegram notification failed", error);
                throw error;
            }
        }, startupAt);
        // The immediate message deliberately uses the persisted snapshot so server start stays light.
        // Recalculate the complete candidate universe after boot traffic and collectors have settled.
        schedulePostStartupCandidateRecalculation(
                startupAt.plus(postStartupRecalculationDelay),
                POST_STARTUP_MAX_ATTEMPTS
        );
    }

    @Scheduled(
            cron = "${macrosquare.notifications.weekday-scan-cron:0 20 * * * 1-5}",
            zone = "Asia/Seoul",
            scheduler = "notificationTaskScheduler"
    )
    public void weekdayCandidateScan() {
        scan("weekday-confirmed-bottom-scan-1h");
    }

    @Scheduled(
            cron = "${macrosquare.notifications.weekend-scan-cron:0 20 */4 * * 0,6}",
            zone = "Asia/Seoul",
            scheduler = "notificationTaskScheduler"
    )
    public void weekendCandidateScan() {
        scan("weekend-confirmed-bottom-scan-4h");
    }

    @Scheduled(
            fixedDelayString = "${macrosquare.notifications.market-check-delay:5m}",
            initialDelayString = "${macrosquare.notifications.market-check-initial-delay:2m}",
            scheduler = "notificationTaskScheduler"
    )
    public void marketChanges() {
        try {
            var executed = exclusiveTasks.execute("notification:market-check", () -> {
                notifications.checkMarketChanges("scheduled-market-check");
                outbox.dispatchPending();
            });
            if (!executed) {
                LOGGER.info("Scheduled market notification check skipped because another instance owns the task");
            }
        } catch (RuntimeException error) {
            LOGGER.error("Scheduled market notification check failed", error);
            throw error;
        }
    }

    @Scheduled(
            cron = "${macrosquare.notifications.weekly-report-cron:0 0 8 * * 1}",
            zone = "Asia/Seoul",
            scheduler = "notificationTaskScheduler"
    )
    public void weeklyReport() {
        try {
            var executed = exclusiveTasks.execute("notification:weekly-report", () -> {
                notifications.dispatchWeeklyReport();
                outbox.dispatchPending();
            });
            if (!executed) {
                LOGGER.info("Scheduled weekly Telegram report skipped because another instance owns the task");
            }
        } catch (RuntimeException error) {
            LOGGER.error("Scheduled weekly Telegram report failed", error);
            throw error;
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Scheduled(
            fixedDelayString = "${macrosquare.notifications.outbox-dispatch-delay:15s}",
            initialDelayString = "${macrosquare.notifications.outbox-dispatch-initial-delay:10s}",
            scheduler = "notificationTaskScheduler"
    )
    public void dispatchOutbox() {
        try {
            var delivered = outbox.dispatchPending();
            if (delivered > 0) LOGGER.info("Telegram outbox dispatch completed (delivered={})", delivered);
        } catch (RuntimeException error) {
            LOGGER.error("Telegram outbox dispatch failed", error);
            throw error;
        }
    }

    @Scheduled(
            cron = "${macrosquare.notifications.outbox-maintenance-cron:0 35 3 * * *}",
            zone = "Asia/Seoul",
            scheduler = "notificationTaskScheduler"
    )
    public void maintainOutbox() {
        try {
            var executed = exclusiveTasks.execute("notification:outbox-maintenance", () -> {
                var cutoff = clock.instant().minus(outboxRetention);
                var purged = outboxMaintenance.purgeTerminalBefore(cutoff);
                if (purged > 0) {
                    LOGGER.info("Terminal notification outbox retention completed (purged={}, cutoff={})",
                            purged, cutoff);
                }
            });
            if (!executed) {
                LOGGER.info("Notification outbox maintenance skipped because another instance owns the task");
            }
        } catch (RuntimeException error) {
            LOGGER.error("Notification outbox maintenance failed", error);
            throw error;
        }
    }

    private void schedulePostStartupCandidateRecalculation(Instant scheduledAt, int attemptsRemaining) {
        scheduler.schedule(() -> {
            var executed = scan("post-startup-candidate-recalculation");
            if (!executed && attemptsRemaining > 1) {
                var retryAt = clock.instant().plus(POST_STARTUP_RETRY_DELAY);
                LOGGER.info("Post-startup candidate recalculation deferred; retry scheduled (at={}, attemptsRemaining={})",
                        retryAt, attemptsRemaining - 1);
                schedulePostStartupCandidateRecalculation(retryAt, attemptsRemaining - 1);
            }
        }, scheduledAt);
    }

    private boolean scan(String trigger) {
        if (!candidateScanActive.compareAndSet(false, true)) {
            LOGGER.info("Spring investment entry notification scan skipped because a local scan is active (trigger={})",
                    trigger);
            return false;
        }
        try {
            var executed = exclusiveTasks.execute(ScheduledTaskNames.COMPANY_PROVIDER_HEAVY, () -> {
                LOGGER.info("Spring investment entry notification scan started (trigger={})", trigger);
                var alerted = notifications.scanCandidates(trigger);
                var delivered = outbox.dispatchPending();
                LOGGER.info("Spring investment entry notification scan completed (trigger={}, alerted={}, delivered={})",
                        trigger, alerted, delivered);
            });
            if (!executed) {
                LOGGER.info("Spring investment entry notification scan deferred because another provider-heavy company job owns the slot (trigger={})",
                        trigger);
            }
            return executed;
        } catch (RuntimeException error) {
            LOGGER.error("Spring investment entry notification scan failed (trigger={})", trigger, error);
            throw error;
        } finally {
            candidateScanActive.set(false);
        }
    }
}
