package io.macrosquare.company.adapter.in.scheduling;

import io.macrosquare.company.application.port.in.RefreshCompanyResearchSummariesUseCase.RefreshReport;
import io.macrosquare.shared.application.port.out.ScheduledTaskNames;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanyResearchSummarySchedulerTest {

    @Test
    void usesTheSharedProviderHeavyCompanySlot() {
        var taskName = new AtomicReference<String>();
        var scheduler = new CompanyResearchSummaryScheduler(
                () -> new RefreshReport(1, 1, List.of()),
                (name, task) -> {
                    taskName.set(name);
                    task.run();
                    return true;
                }
        );

        scheduler.refresh();

        assertEquals(ScheduledTaskNames.COMPANY_PROVIDER_HEAVY, taskName.get());
    }
}
