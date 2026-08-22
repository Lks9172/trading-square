package io.macrosquare.company.adapter.in.scheduling;

import io.macrosquare.company.application.port.in.CompanyAnalystHistoryRecordReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.macrosquare.shared.application.port.out.ScheduledTaskNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyAnalystHistorySchedulerTest {

    @Test
    void preventsOverlappingStartupAndCronRuns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var scheduler = new CompanyAnalystHistoryScheduler(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return success();
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(scheduler::recordAfterStartup);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.recordWeekday();
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, calls.get());
    }

    @Test
    void allowsTheNextRunAfterCompletion() {
        var calls = new AtomicInteger();
        var scheduler = new CompanyAnalystHistoryScheduler(() -> {
            calls.incrementAndGet();
            return success();
        });

        scheduler.recordAfterStartup();
        scheduler.recordWeekend();

        assertEquals(2, calls.get());
    }

    @Test
    void skipsTheUseCaseWhenAnotherInstanceOwnsTheClusterTask() {
        var calls = new AtomicInteger();
        var scheduler = new CompanyAnalystHistoryScheduler(
                () -> {
                    calls.incrementAndGet();
                    return success();
                },
                (taskName, task) -> false
        );

        scheduler.recordAfterStartup();

        assertEquals(0, calls.get());
    }

    @Test
    void usesTheSharedProviderHeavyCompanySlot() {
        var taskName = new AtomicReference<String>();
        var scheduler = new CompanyAnalystHistoryScheduler(
                CompanyAnalystHistorySchedulerTest::success,
                (name, task) -> {
                    taskName.set(name);
                    task.run();
                    return true;
                }
        );

        scheduler.recordAfterStartup();

        assertEquals(ScheduledTaskNames.COMPANY_PROVIDER_HEAVY, taskName.get());
    }

    private static CompanyAnalystHistoryRecordReport success() {
        var instant = Instant.parse("2026-07-19T15:30:00Z");
        return new CompanyAnalystHistoryRecordReport(
                instant,
                instant,
                LocalDate.parse("2026-07-19"),
                7,
                7,
                7,
                List.of()
        );
    }
}
