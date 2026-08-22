package io.macrosquare.integrity.adapter.in.scheduling;

import io.macrosquare.integrity.application.model.DataIntegrityCheckResult;
import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.application.port.in.CheckDataIntegrityUseCase;
import io.macrosquare.integrity.domain.DataIntegrityReport;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataIntegritySchedulerTest {

    @Test
    void releasesTheLocalGuardAfterACheckFailureSoTheNextRunCanRecover() {
        var calls = new AtomicInteger();
        CheckDataIntegrityUseCase check = trigger -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("simulated evidence failure");
            return new DataIntegrityCheckResult(
                    new DataIntegrityReport(Instant.EPOCH, List.of(), List.of()),
                    IntegrityIncidentTransition.HEALTHY
            );
        };
        ExclusiveTaskExecution exclusive = (taskName, task) -> {
            task.run();
            return true;
        };
        var scheduler = new DataIntegrityScheduler(check, exclusive);

        assertThrows(IllegalStateException.class, scheduler::check);
        scheduler.check();

        assertEquals(2, calls.get());
    }

    @Test
    void activeIncidentWarningsAreBoundedButKeepPeriodicReminders() {
        assertTrue(DataIntegrityScheduler.shouldLogActiveReminder(1));
        assertFalse(DataIntegrityScheduler.shouldLogActiveReminder(2));
        assertFalse(DataIntegrityScheduler.shouldLogActiveReminder(29));
        assertTrue(DataIntegrityScheduler.shouldLogActiveReminder(30));
        assertTrue(DataIntegrityScheduler.shouldLogActiveReminder(60));
        assertThrows(IllegalArgumentException.class,
                () -> DataIntegrityScheduler.shouldLogActiveReminder(0));
    }
}
