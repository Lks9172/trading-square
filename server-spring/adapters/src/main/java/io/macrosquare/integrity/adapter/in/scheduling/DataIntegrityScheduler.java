package io.macrosquare.integrity.adapter.in.scheduling;

import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.application.port.in.CheckDataIntegrityUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** One-minute recurrence detector for persisted investment-data invariants. */
public final class DataIntegrityScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataIntegrityScheduler.class);
    private final CheckDataIntegrityUseCase check;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean healthyPassLogged = new AtomicBoolean();
    private final AtomicReference<String> activeFingerprint = new AtomicReference<>();
    private final AtomicInteger activeReminderChecks = new AtomicInteger();

    public DataIntegrityScheduler(
            CheckDataIntegrityUseCase check,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.check = Objects.requireNonNull(check);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.integrity-monitor.initial-delay:30s}",
            fixedDelayString = "${macrosquare.integrity-monitor.fixed-delay:1m}",
            scheduler = "dataIntegrityTaskScheduler"
    )
    public void check() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var executed = exclusiveTasks.execute("integrity:recurrence-check", () -> {
                var result = check.check("scheduled-recurrence-check");
                if (result.report().healthy()) {
                    clearActiveReminder();
                    if (result.transition() == IntegrityIncidentTransition.RECOVERED) {
                        healthyPassLogged.set(true);
                        LOGGER.info("Data integrity incident recovered and notification queued");
                    } else if (healthyPassLogged.compareAndSet(false, true)) {
                        LOGGER.info("Data integrity recurrence monitor is active and all invariants passed");
                    } else {
                        LOGGER.debug("Data integrity recurrence check passed");
                    }
                } else if (result.transition() == IntegrityIncidentTransition.NEW_ALERT) {
                    healthyPassLogged.set(false);
                    activeFingerprint.set(result.report().fingerprint());
                    activeReminderChecks.set(0);
                    LOGGER.error(
                            "Data integrity recurrence detected (fingerprint={}, violations={}, failureSources={})",
                            result.report().fingerprint(), result.report().violations(),
                            result.report().hardCollectionSources());
                } else {
                    healthyPassLogged.set(false);
                    var reminderCheck = nextActiveReminderCheck(result.report().fingerprint());
                    if (shouldLogActiveReminder(reminderCheck)) {
                        LOGGER.warn(
                                "Data integrity incident remains active (fingerprint={}, violationCount={}, activeChecks={})",
                                result.report().fingerprint(), result.report().violations().size(), reminderCheck);
                    } else {
                        LOGGER.debug(
                                "Data integrity incident still active (fingerprint={}, violationCount={}, activeChecks={})",
                                result.report().fingerprint(), result.report().violations().size(), reminderCheck);
                    }
                }
            });
            if (!executed) {
                LOGGER.info("Data integrity recurrence check owned by another instance");
            }
        } catch (RuntimeException error) {
            LOGGER.error("Data integrity recurrence check itself failed", error);
            throw error;
        } finally {
            running.set(false);
        }
    }

    private int nextActiveReminderCheck(String fingerprint) {
        var previous = activeFingerprint.getAndSet(fingerprint);
        if (!Objects.equals(previous, fingerprint)) {
            activeReminderChecks.set(1);
            return 1;
        }
        return activeReminderChecks.incrementAndGet();
    }

    private void clearActiveReminder() {
        activeFingerprint.set(null);
        activeReminderChecks.set(0);
    }

    static boolean shouldLogActiveReminder(int activeChecks) {
        if (activeChecks < 1) throw new IllegalArgumentException("activeChecks must be positive");
        return activeChecks == 1 || activeChecks % 30 == 0;
    }
}
