package io.macrosquare.notification.adapter.in.scheduling;

import io.macrosquare.notification.application.port.in.NotificationOrchestrationUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxDispatchUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxMaintenanceUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.application.port.out.ScheduledTaskNames;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-07-21T03:00:00Z");

    @Test
    void purgesTerminalRowsUsingTheConfiguredRetentionCutoff() {
        var maintenance = mock(NotificationOutboxMaintenanceUseCase.class);
        var scheduler = scheduler(maintenance, Duration.ofDays(30));

        scheduler.maintainOutbox();

        verify(maintenance).purgeTerminalBefore(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void rejectsAZeroRetentionWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> scheduler(mock(NotificationOutboxMaintenanceUseCase.class), Duration.ZERO));
    }

    @Test
    void rejectsAZeroPostStartupRecalculationDelay() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationScheduler(
                mock(NotificationOrchestrationUseCase.class),
                mock(NotificationOutboxDispatchUseCase.class),
                cutoff -> 0,
                mock(TaskScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ZERO,
                Duration.ZERO,
                Duration.ofDays(30),
                ExclusiveTaskExecution.local()));
    }

    @Test
    void schedulesLightweightStartupAndADelayedCandidateRecalculation() {
        var taskScheduler = mock(TaskScheduler.class);
        var scheduler = new NotificationScheduler(
                mock(NotificationOrchestrationUseCase.class),
                mock(NotificationOutboxDispatchUseCase.class),
                cutoff -> 0,
                taskScheduler,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5),
                Duration.ofMinutes(20),
                Duration.ofDays(30),
                ExclusiveTaskExecution.local());

        scheduler.run(null);

        verify(taskScheduler).schedule(any(Runnable.class), eq(NOW.plusSeconds(5)));
        verify(taskScheduler).schedule(any(Runnable.class), eq(NOW.plusSeconds(5).plus(Duration.ofMinutes(20))));
    }

    @Test
    void retriesPostStartupRecalculationWhenTheSharedProviderSlotIsBusy() {
        var taskScheduler = mock(TaskScheduler.class);
        var tasks = new ArrayList<Runnable>();
        var instants = new ArrayList<Instant>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            instants.add(invocation.getArgument(1));
            return null;
        });
        var taskName = new AtomicReference<String>();
        var scheduler = new NotificationScheduler(
                mock(NotificationOrchestrationUseCase.class),
                mock(NotificationOutboxDispatchUseCase.class),
                cutoff -> 0,
                taskScheduler,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(5),
                Duration.ofMinutes(20),
                Duration.ofDays(30),
                (name, task) -> {
                    taskName.set(name);
                    return false;
                });

        scheduler.run(null);
        tasks.get(1).run();

        assertEquals(ScheduledTaskNames.COMPANY_PROVIDER_HEAVY, taskName.get());
        assertEquals(3, tasks.size());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), instants.get(2));
    }

    private static NotificationScheduler scheduler(
            NotificationOutboxMaintenanceUseCase maintenance,
            Duration retention
    ) {
        return new NotificationScheduler(
                mock(NotificationOrchestrationUseCase.class),
                mock(NotificationOutboxDispatchUseCase.class),
                maintenance,
                mock(TaskScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ZERO,
                Duration.ofMinutes(20),
                retention,
                ExclusiveTaskExecution.local());
    }
}
