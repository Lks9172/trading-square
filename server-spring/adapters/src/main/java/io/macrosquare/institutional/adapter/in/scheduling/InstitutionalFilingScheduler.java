package io.macrosquare.institutional.adapter.in.scheduling;

import io.macrosquare.institutional.application.port.in.RefreshInstitutionalFilingsUseCase;
import io.macrosquare.shared.adapter.in.scheduling.ScheduledTaskExecutionException;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InstitutionalFilingScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstitutionalFilingScheduler.class);
    private final RefreshInstitutionalFilingsUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean firstInvocation = new AtomicBoolean(true);
    private final Clock clock;
    private final Duration startupFreshness;

    public InstitutionalFilingScheduler(
            RefreshInstitutionalFilingsUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        this(refresh, exclusiveTasks, Clock.systemUTC(), Duration.ZERO);
    }

    public InstitutionalFilingScheduler(
            RefreshInstitutionalFilingsUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks,
            Clock clock,
            Duration startupFreshness
    ) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
        this.clock = Objects.requireNonNull(clock);
        this.startupFreshness = Objects.requireNonNull(startupFreshness);
        if (startupFreshness.isNegative()) {
            throw new IllegalArgumentException("startupFreshness must not be negative");
        }
    }

    @Scheduled(
            initialDelayString = "${macrosquare.institutional-collection.startup-delay:30s}",
            fixedDelayString = "${macrosquare.institutional-collection.fixed-delay:24h}",
            scheduler = "institutionalTaskScheduler"
    )
    public void collect() {
        if (!running.compareAndSet(false, true)) return;
        var startup = firstInvocation.getAndSet(false);
        try {
            var executed = exclusiveTasks.execute("institutional:sec-13f", () -> {
                var optionalReport = startup && !startupFreshness.isZero()
                        ? refresh.refreshIfStale(clock.instant().minus(startupFreshness))
                        : java.util.Optional.of(refresh.refresh());
                if (optionalReport.isEmpty()) {
                    LOGGER.info("SEC 13F startup collection skipped because durable evidence is current (freshness={})",
                            startupFreshness);
                    return;
                }
                var report = optionalReport.get();
                if (report.successful()) {
                    LOGGER.info(
                            "SEC 13F collection completed (managers={}, filings={}, holdings={}, identities={})",
                            report.managerCount(), report.filingCount(), report.holdingCount(),
                            report.resolvedIdentityCount());
                } else {
                    LOGGER.warn(
                            "SEC 13F collection completed with gaps (managers={}, filings={}, holdings={}, identities={}, failures={})",
                            report.managerCount(), report.filingCount(), report.holdingCount(),
                            report.resolvedIdentityCount(), report.failures());
                    throw new ScheduledTaskExecutionException(
                            "institutional-sec-13f", "incomplete institutional refresh: " + report.failures());
                }
            });
            if (!executed) LOGGER.info("SEC 13F collection skipped because another instance owns the task");
        } finally {
            running.set(false);
        }
    }
}
