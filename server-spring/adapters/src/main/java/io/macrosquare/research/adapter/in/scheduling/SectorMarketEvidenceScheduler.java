package io.macrosquare.research.adapter.in.scheduling;

import io.macrosquare.research.application.port.in.RefreshSectorMarketEvidenceUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes official sector-fund flow and tracked-constituent breadth on a dedicated lane. */
public final class SectorMarketEvidenceScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SectorMarketEvidenceScheduler.class);
    private static final String SCHEDULER = "researchSectorEvidenceTaskScheduler";
    private static final String TASK = "research:sector-market-evidence";

    private final RefreshSectorMarketEvidenceUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public SectorMarketEvidenceScheduler(
            RefreshSectorMarketEvidenceUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.sector-market-evidence.startup-delay:3m}",
            fixedDelayString = "${macrosquare.sector-market-evidence.fixed-delay:6h}",
            scheduler = SCHEDULER
    )
    public void refreshScheduled() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.info("Sector market evidence refresh skipped because another local run is active");
            return;
        }
        try {
            var executed = exclusiveTasks.execute(TASK, () -> {
                var report = refresh.refresh();
                if (report.successful()) {
                    LOGGER.info(
                            "Sector market evidence completed (sectors={}, fundFlow={}, priceBreadth={}, durationMs={})",
                            report.attemptedSectors(), report.fundFlowWritten(), report.priceBreadthWritten(),
                            report.completedAt().toEpochMilli() - report.startedAt().toEpochMilli());
                } else {
                    LOGGER.warn(
                            "Sector market evidence completed with gaps (sectors={}, fundFlow={}, priceBreadth={}, failures={})",
                            report.attemptedSectors(), report.fundFlowWritten(), report.priceBreadthWritten(),
                            report.failures().stream().map(value -> value.sectorKey() + "/"
                                    + value.evidenceType() + "=" + value.reason()).toList());
                }
            });
            if (!executed) LOGGER.info("Sector market evidence refresh skipped because another instance owns the task");
        } catch (RuntimeException error) {
            LOGGER.error("Sector market evidence refresh failed (errorType={})", error.getClass().getSimpleName(), error);
        } finally {
            running.set(false);
        }
    }
}
