package io.macrosquare.research.adapter.in.scheduling;

import io.macrosquare.research.application.port.in.RefreshPeerTaxonomyUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PeerTaxonomyScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PeerTaxonomyScheduler.class);
    private final RefreshPeerTaxonomyUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public PeerTaxonomyScheduler(RefreshPeerTaxonomyUseCase refresh, ExclusiveTaskExecution exclusiveTasks) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.peer-discovery.startup-delay:2m}",
            fixedDelayString = "${macrosquare.peer-discovery.fixed-delay:6h}",
            scheduler = "peerTaxonomyTaskScheduler"
    )
    public void collect() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var executed = exclusiveTasks.execute("research:peer-taxonomy", () -> {
                var report = refresh.refresh();
                var noSic = report.failures().stream().filter(value -> value.endsWith(":NO_SIC")).count();
                var retryable = report.failures().size() - noSic;
                if (retryable > 0) {
                    LOGGER.warn("SEC peer taxonomy refresh completed with retryable gaps (universe={}, attempted={}, persisted={}, noSic={}, retryableFailures={}, samples={})",
                            report.universeCount(), report.attemptedCount(), report.persistedCount(), noSic,
                            retryable, report.failures().stream().filter(value -> !value.endsWith(":NO_SIC")).limit(10).toList());
                } else {
                    LOGGER.info("SEC peer taxonomy refresh completed (universe={}, attempted={}, persisted={}, noSic={})",
                            report.universeCount(), report.attemptedCount(), report.persistedCount(), noSic);
                }
            });
            if (!executed) LOGGER.info("SEC peer taxonomy refresh skipped because another instance owns the task");
        } finally {
            running.set(false);
        }
    }
}
