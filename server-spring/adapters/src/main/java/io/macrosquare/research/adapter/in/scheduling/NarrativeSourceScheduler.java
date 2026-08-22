package io.macrosquare.research.adapter.in.scheduling;

import io.macrosquare.research.application.port.in.RefreshNarrativeSourcesUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NarrativeSourceScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NarrativeSourceScheduler.class);
    private final RefreshNarrativeSourcesUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public NarrativeSourceScheduler(
            RefreshNarrativeSourcesUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.narrative-sources.startup-delay:90s}",
            fixedDelayString = "${macrosquare.narrative-sources.fixed-delay:6h}",
            scheduler = "narrativeSourceTaskScheduler"
    )
    public void collect() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var executed = exclusiveTasks.execute("research:narrative-sources", () -> {
                var report = refresh.refresh();
                LOGGER.info(
                        "Narrative source refresh completed (attempted={}, persisted={}, available={}, missing={}, failed={})",
                        report.attemptedCount(), report.persistedCount(), report.availableCount(),
                        report.missingCount(), report.failedCount());
            });
            if (!executed) LOGGER.info("Narrative source refresh skipped because another instance owns the task");
        } finally {
            running.set(false);
        }
    }
}
