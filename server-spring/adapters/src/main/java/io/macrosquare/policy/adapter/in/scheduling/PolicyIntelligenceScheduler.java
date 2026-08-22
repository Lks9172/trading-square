package io.macrosquare.policy.adapter.in.scheduling;

import io.macrosquare.policy.application.port.in.RefreshPolicyIntelligenceUseCase;
import io.macrosquare.shared.adapter.in.scheduling.ScheduledTaskExecutionException;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PolicyIntelligenceScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyIntelligenceScheduler.class);
    private final RefreshPolicyIntelligenceUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public PolicyIntelligenceScheduler(
            RefreshPolicyIntelligenceUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.policy-collection.startup-delay:40s}",
            fixedDelayString = "${macrosquare.policy-collection.fixed-delay:6h}",
            scheduler = "policyTaskScheduler"
    )
    public void collect() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var executed = exclusiveTasks.execute("policy:official-documents", () -> {
                var report = refresh.refresh();
                if (report.successful()) {
                    LOGGER.info(
                            "Official policy collection completed (collected={}, persisted={})",
                            report.collected(), report.persisted());
                } else {
                    LOGGER.warn(
                            "Official policy collection completed with gaps (collected={}, persisted={}, failures={})",
                            report.collected(), report.persisted(), report.failures());
                    throw new ScheduledTaskExecutionException(
                            "policy-official-documents", "incomplete policy refresh: " + report.failures());
                }
            });
            if (!executed) LOGGER.info("Official policy collection skipped because another instance owns the task");
        } finally {
            running.set(false);
        }
    }
}
