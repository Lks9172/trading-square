package io.macrosquare.institutional.adapter.in.scheduling;

import io.macrosquare.institutional.application.model.InstitutionalRefreshReport;
import io.macrosquare.institutional.application.port.in.RefreshInstitutionalFilingsUseCase;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstitutionalFilingSchedulerTest {

    @Test
    void onlyTheStartupInvocationCanReuseRecentDurableCollection() {
        var now = Instant.parse("2026-08-17T05:00:00Z");
        var staleChecks = new AtomicInteger();
        var refreshes = new AtomicInteger();
        RefreshInstitutionalFilingsUseCase refresh = new RefreshInstitutionalFilingsUseCase() {
            @Override public InstitutionalRefreshReport refresh() {
                refreshes.incrementAndGet();
                return report(now);
            }

            @Override public Optional<InstitutionalRefreshReport> refreshIfStale(Instant freshAfter) {
                staleChecks.incrementAndGet();
                assertEquals(now.minus(Duration.ofHours(2)), freshAfter);
                return Optional.empty();
            }
        };
        var scheduler = new InstitutionalFilingScheduler(
                refresh,
                ExclusiveTaskExecution.local(),
                Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofHours(2)
        );

        scheduler.collect();
        scheduler.collect();

        assertEquals(1, staleChecks.get());
        assertEquals(1, refreshes.get());
    }

    private static InstitutionalRefreshReport report(Instant now) {
        return new InstitutionalRefreshReport(now, now, 1, 1, 1, 0, List.of());
    }
}
