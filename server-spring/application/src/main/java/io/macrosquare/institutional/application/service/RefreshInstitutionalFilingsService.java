package io.macrosquare.institutional.application.service;

import io.macrosquare.institutional.application.model.InstitutionalRefreshReport;
import io.macrosquare.institutional.application.port.in.RefreshInstitutionalFilingsUseCase;
import io.macrosquare.institutional.application.port.out.CollectInstitutionalFilingsPort;
import io.macrosquare.institutional.application.port.out.InstitutionalFilingRepository;
import io.macrosquare.institutional.application.port.out.InstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.application.port.out.ResolveInstitutionalSecurityIdentitiesPort;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityObservation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class RefreshInstitutionalFilingsService implements RefreshInstitutionalFilingsUseCase {

    private final CollectInstitutionalFilingsPort collector;
    private final InstitutionalFilingRepository repository;
    private final ResolveInstitutionalSecurityIdentitiesPort identityResolver;
    private final InstitutionalSecurityIdentityRepository identityRepository;
    private final List<InstitutionalManager> managers;
    private final int filingLimit;
    private final Clock clock;

    public RefreshInstitutionalFilingsService(
            CollectInstitutionalFilingsPort collector,
            InstitutionalFilingRepository repository,
            List<InstitutionalManager> managers,
            int filingLimit,
            Clock clock
    ) {
        this(collector, repository, observations -> List.of(), null, managers, filingLimit, clock);
    }

    public RefreshInstitutionalFilingsService(
            CollectInstitutionalFilingsPort collector,
            InstitutionalFilingRepository repository,
            ResolveInstitutionalSecurityIdentitiesPort identityResolver,
            InstitutionalSecurityIdentityRepository identityRepository,
            List<InstitutionalManager> managers,
            int filingLimit,
            Clock clock
    ) {
        this.collector = Objects.requireNonNull(collector);
        this.repository = Objects.requireNonNull(repository);
        this.identityResolver = Objects.requireNonNull(identityResolver);
        this.identityRepository = identityRepository;
        this.managers = List.copyOf(managers);
        if (this.managers.isEmpty()) throw new IllegalArgumentException("at least one institutional manager is required");
        if (filingLimit < 2 || filingLimit > 8) throw new IllegalArgumentException("filingLimit must be between 2 and 8");
        this.filingLimit = filingLimit;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public InstitutionalRefreshReport refresh() {
        var started = clock.instant();
        var all = new ArrayList<InstitutionalFiling>();
        var failures = new ArrayList<String>();
        for (var manager : managers) {
            try {
                var collected = collector.collect(manager, filingLimit);
                if (collected.isEmpty()) failures.add(manager.id() + ":no-filings");
                else all.addAll(collected);
            } catch (RuntimeException error) {
                failures.add(manager.id() + ":" + error.getClass().getSimpleName());
            }
        }
        var persisted = all.isEmpty() ? 0 : repository.save(all);
        if (persisted != all.size()) {
            failures.add("filing-persistence-count-mismatch:expected=" + all.size() + ":actual=" + persisted);
        }
        var holdings = all.stream().mapToInt(value -> value.holdings().size()).sum();
        var resolved = resolveIdentities(all, failures);
        return new InstitutionalRefreshReport(
                started, clock.instant(), managers.size(), persisted, holdings, resolved, failures);
    }

    @Override
    public Optional<InstitutionalRefreshReport> refreshIfStale(Instant freshAfter) {
        Objects.requireNonNull(freshAfter, "freshAfter");
        var current = repository.latestCollectedAt(
                managers.stream().map(InstitutionalManager::cik).toList());
        if (current.isPresent() && !current.get().isBefore(freshAfter)) return Optional.empty();
        return Optional.of(refresh());
    }

    private int resolveIdentities(List<InstitutionalFiling> filings, List<String> failures) {
        if (filings.isEmpty() || identityRepository == null) return 0;
        try {
            var latest = new LinkedHashMap<String, InstitutionalSecurityObservation>();
            for (var filing : filings) {
                for (var holding : filing.holdings()) {
                    var observation = new InstitutionalSecurityObservation(
                            holding.cusip(), holding.issuer(), filing.reportPeriod());
                    latest.merge(observation.cusip(), observation, (left, right) ->
                            right.reportPeriod().isAfter(left.reportPeriod()) ? right : left);
                }
            }
            var identities = identityResolver.resolve(List.copyOf(latest.values()));
            return identities.isEmpty() ? 0 : identityRepository.savePointInTime(identities);
        } catch (RuntimeException error) {
            failures.add("security-identity:" + error.getClass().getSimpleName());
            return 0;
        }
    }
}
