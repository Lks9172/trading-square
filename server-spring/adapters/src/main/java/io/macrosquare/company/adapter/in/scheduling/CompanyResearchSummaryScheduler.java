package io.macrosquare.company.adapter.in.scheduling;

import io.macrosquare.company.application.port.in.RefreshCompanyResearchSummariesUseCase;
import io.macrosquare.shared.adapter.in.scheduling.ScheduledTaskExecutionException;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.application.port.out.ScheduledTaskNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CompanyResearchSummaryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompanyResearchSummaryScheduler.class);
    private final RefreshCompanyResearchSummariesUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public CompanyResearchSummaryScheduler(
            RefreshCompanyResearchSummariesUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.company-research-summary.startup-delay:15s}",
            fixedDelayString = "${macrosquare.company-research-summary.fixed-delay:30m}",
            scheduler = "companyResearchSummaryTaskScheduler"
    )
    public void refresh() {
        if (!running.compareAndSet(false, true)) return;
        var invocationStartedAt = System.nanoTime();
        try {
            var executed = exclusiveTasks.execute(ScheduledTaskNames.COMPANY_PROVIDER_HEAVY, () -> {
                var startedAt = System.nanoTime();
                LOGGER.info("Company research summary refresh started");
                var report = refresh.refreshAll();
                if (report.successful()) {
                    LOGGER.info("Company research summaries refreshed (attempted={}, written={}, durationMs={})",
                            report.attempted(), report.written(), elapsedMillis(startedAt));
                } else {
                    LOGGER.warn("Company research summary refresh was partial (attempted={}, written={}, failures={}, durationMs={})",
                            report.attempted(), report.written(), report.failures(), elapsedMillis(startedAt));
                    if (report.written() == 0) {
                        throw new ScheduledTaskExecutionException(
                                "company-research-summary", "all refreshes failed");
                    }
                }
            });
            if (!executed) {
                LOGGER.info("Company research summary refresh deferred because another provider-heavy company job owns the slot");
            }
        } catch (RuntimeException error) {
            LOGGER.error("Company research summary refresh failed (errorType={}, durationMs={})",
                    error.getClass().getSimpleName(), elapsedMillis(invocationStartedAt));
            throw error;
        } finally {
            running.set(false);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
