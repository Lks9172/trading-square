package io.macrosquare.disclosure.adapter.in.scheduling;

import io.macrosquare.disclosure.application.port.in.RefreshDartUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DartDisclosureScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DartDisclosureScheduler.class);
    private final RefreshDartUseCase refresh;
    private final ExclusiveTaskExecution exclusiveTasks;
    private final AtomicBoolean running = new AtomicBoolean();

    public DartDisclosureScheduler(RefreshDartUseCase refresh, ExclusiveTaskExecution exclusiveTasks) {
        this.refresh = Objects.requireNonNull(refresh);
        this.exclusiveTasks = Objects.requireNonNull(exclusiveTasks);
    }

    @Scheduled(
            initialDelayString = "${macrosquare.dart.startup-delay:3m}",
            fixedDelayString = "${macrosquare.dart.fixed-delay:6h}",
            scheduler = "dartTaskScheduler"
    )
    public void collect() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var executed = exclusiveTasks.execute("disclosure:opendart", () -> {
                var report = refresh.refresh();
                LOGGER.info("OpenDART refresh completed (companies={}, disclosures={}, financials={}, failures={})",
                        report.companyCount(), report.disclosureCount(), report.financialMetricCount(),
                        report.failures().size());
            });
            if (!executed) LOGGER.info("OpenDART refresh skipped because another instance owns the task");
        } finally {
            running.set(false);
        }
    }
}
