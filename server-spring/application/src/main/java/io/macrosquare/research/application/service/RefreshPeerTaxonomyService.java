package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.PeerTaxonomyRefreshReport;
import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.application.model.PeerTaxonomyUnavailableException;
import io.macrosquare.research.application.port.in.RefreshPeerTaxonomyUseCase;
import io.macrosquare.research.application.port.out.CollectPeerTaxonomyPort;
import io.macrosquare.research.application.port.out.LoadPeerUniversePort;
import io.macrosquare.research.application.port.out.LoadPriorityPeerTickersPort;
import io.macrosquare.research.application.port.out.PeerTaxonomyRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class RefreshPeerTaxonomyService implements RefreshPeerTaxonomyUseCase {

    private final LoadPeerUniversePort universeLoader;
    private final LoadPriorityPeerTickersPort priorityTickers;
    private final CollectPeerTaxonomyPort collector;
    private final PeerTaxonomyRepository repository;
    private final Clock clock;
    private final int batchSize;
    private final Duration refreshTtl;
    private final Duration missingGrace;

    public RefreshPeerTaxonomyService(
            LoadPeerUniversePort universeLoader,
            LoadPriorityPeerTickersPort priorityTickers,
            CollectPeerTaxonomyPort collector,
            PeerTaxonomyRepository repository,
            Clock clock,
            int batchSize,
            Duration refreshTtl,
            Duration missingGrace
    ) {
        this.universeLoader = Objects.requireNonNull(universeLoader);
        this.priorityTickers = Objects.requireNonNull(priorityTickers);
        this.collector = Objects.requireNonNull(collector);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize is out of range");
        this.batchSize = batchSize;
        this.refreshTtl = positive(refreshTtl, "refreshTtl");
        this.missingGrace = positive(missingGrace, "missingGrace");
    }

    @Override
    public PeerTaxonomyRefreshReport refresh() {
        var started = clock.instant();
        var universe = universeLoader.load();
        repository.reconcileDirectory(universe, started, missingGrace);
        var priority = safePriority();
        var refreshed = repository.loadRefreshTimes();
        var staleBefore = started.minus(refreshTtl);
        var selected = universe.stream()
                .filter(value -> {
                    var last = refreshed.get(value.ticker().toUpperCase(Locale.ROOT));
                    return last == null || last.isBefore(staleBefore);
                })
                .sorted(Comparator
                        .comparing((PeerUniverseCompany value) -> !priority.contains(value.ticker().toUpperCase(Locale.ROOT)))
                        .thenComparing(value -> refreshed.getOrDefault(
                                value.ticker().toUpperCase(Locale.ROOT), Instant.EPOCH))
                        .thenComparing(PeerUniverseCompany::ticker))
                .limit(batchSize).toList();
        var observedOn = LocalDate.ofInstant(started, ZoneOffset.UTC);
        var values = new ArrayList<io.macrosquare.research.domain.peer.PeerTaxonomy>();
        var failures = new ArrayList<String>();
        var checked = new ArrayList<String>();
        for (var company : selected) {
            try {
                values.add(collector.collect(company, observedOn));
                checked.add(company.ticker());
            } catch (PeerTaxonomyUnavailableException error) {
                // Foreign ADRs, shells and newly registered issuers can legitimately omit SIC.
                // Remember the check so they do not starve the entire discovery queue every run.
                failures.add(company.ticker() + ":NO_SIC");
                checked.add(company.ticker());
            } catch (RuntimeException error) {
                failures.add(company.ticker() + ":" + error.getClass().getSimpleName());
            }
        }
        var persisted = values.isEmpty() ? 0 : repository.save(values, clock.instant());
        if (!checked.isEmpty()) repository.markChecked(checked, clock.instant());
        return new PeerTaxonomyRefreshReport(
                started, clock.instant(), universe.size(), selected.size(), persisted, failures);
    }

    private Set<String> safePriority() {
        try {
            return priorityTickers.load();
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
