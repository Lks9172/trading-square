package io.macrosquare.institutional.application.service;

import io.macrosquare.institutional.application.port.out.InstitutionalFilingRepository;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshInstitutionalFilingsServiceTest {

    @Test
    void skipsProviderAndHoldingRewritesWhenDurableCollectionIsFresh() {
        var now = Instant.parse("2026-08-17T05:00:00Z");
        var repository = new InstitutionalFilingRepository() {
            @Override public int save(List<InstitutionalFiling> filings) {
                throw new AssertionError("fresh collection must not be rewritten");
            }

            @Override public List<InstitutionalFiling> loadLatestPerManager(int filingLimit) {
                return List.of();
            }

            @Override public Optional<Instant> latestCollectedAt(List<String> managerCiks) {
                assertTrue(managerCiks.contains("0000000001"));
                return Optional.of(now.minusSeconds(300));
            }
        };
        var service = new RefreshInstitutionalFilingsService(
                (manager, filingLimit) -> {
                    throw new AssertionError("fresh collection must not call SEC");
                },
                repository,
                List.of(new InstitutionalManager("test", "Test Manager", "0000000001")),
                2,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        assertTrue(service.refreshIfStale(now.minusSeconds(7_200)).isEmpty());
    }

    @Test
    void refreshesAllManagersWhenDurableEvidenceIsIncompleteAndReportsEmptyManagers() {
        var now = Instant.parse("2026-08-17T05:00:00Z");
        var collectedManagers = new ArrayList<String>();
        var persisted = new ArrayList<InstitutionalFiling>();
        var first = new InstitutionalManager("first", "First Manager", "0000000001");
        var second = new InstitutionalManager("second", "Second Manager", "0000000002");
        var repository = new InstitutionalFilingRepository() {
            @Override public int save(List<InstitutionalFiling> filings) {
                persisted.addAll(filings);
                return filings.size();
            }

            @Override public List<InstitutionalFiling> loadLatestPerManager(int filingLimit) {
                return List.of();
            }

            @Override public Optional<Instant> latestCollectedAt(List<String> managerCiks) {
                assertEquals(List.of(first.cik(), second.cik()), managerCiks);
                return Optional.empty();
            }
        };
        var service = new RefreshInstitutionalFilingsService(
                (manager, filingLimit) -> {
                    collectedManagers.add(manager.cik());
                    if (manager.equals(second)) return List.of();
                    return List.of(new InstitutionalFiling(
                            manager,
                            "0000000001-26-000001",
                            LocalDate.parse("2026-05-15"),
                            LocalDate.parse("2026-03-31"),
                            "https://www.sec.gov/Archives/example.xml",
                            "sec-filings/13f/example.xml",
                            List.of()));
                },
                repository,
                List.of(first, second),
                2,
                Clock.fixed(now, ZoneOffset.UTC)
        );

        var report = service.refreshIfStale(now.minusSeconds(7_200)).orElseThrow();

        assertEquals(List.of(first.cik(), second.cik()), collectedManagers);
        assertEquals(1, persisted.size());
        assertTrue(report.failures().contains("second:no-filings"));
    }
}
