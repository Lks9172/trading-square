package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.PeerTaxonomyUnavailableException;
import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.application.port.out.PeerTaxonomyRepository;
import io.macrosquare.research.domain.peer.PeerTaxonomy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshPeerTaxonomyServiceTest {

    @Test
    void advancesPastPermanentNoSicEntriesWithoutCoolingDownTransientFailures() {
        var companies = List.of(
                new PeerUniverseCompany("GOOD", "0000000001", "Good Inc"),
                new PeerUniverseCompany("NOSIC", "0000000002", "Foreign ADR"),
                new PeerUniverseCompany("RETRY", "0000000003", "Temporary Failure")
        );
        var repository = new RecordingRepository();
        var clock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);
        var service = new RefreshPeerTaxonomyService(
                () -> companies,
                Set::of,
                (company, observedOn) -> switch (company.ticker()) {
                    case "NOSIC" -> throw new PeerTaxonomyUnavailableException("missing SIC");
                    case "RETRY" -> throw new IllegalStateException("temporary SEC error");
                    default -> new PeerTaxonomy(
                            company.ticker(), company.cik(), company.companyName(), 3571,
                            "Electronic Computers", "technology", observedOn, null);
                },
                repository,
                clock,
                10,
                Duration.ofDays(30),
                Duration.ofDays(30)
        );

        var report = service.refresh();

        assertEquals(1, report.persistedCount());
        assertTrue(report.failures().contains("NOSIC:NO_SIC"));
        assertTrue(report.failures().contains("RETRY:IllegalStateException"));
        assertEquals(List.of("GOOD", "NOSIC"), repository.checked.stream().sorted().toList());
    }

    private static final class RecordingRepository implements PeerTaxonomyRepository {
        private final List<String> checked = new ArrayList<>();

        @Override public int save(List<PeerTaxonomy> taxonomies, Instant refreshedAt) { return taxonomies.size(); }
        @Override public void reconcileDirectory(List<PeerUniverseCompany> universe, Instant observedAt, Duration missingGrace) { }
        @Override public Map<String, Instant> loadRefreshTimes() { return Map.of(); }
        @Override public void markChecked(List<String> tickers, Instant checkedAt) { checked.addAll(tickers); }
        @Override public PeerTaxonomy findAsOf(String ticker, LocalDate asOf) { return null; }
        @Override public List<PeerTaxonomy> loadCandidates(PeerTaxonomy target, LocalDate asOf, int limit) { return List.of(); }
    }
}
