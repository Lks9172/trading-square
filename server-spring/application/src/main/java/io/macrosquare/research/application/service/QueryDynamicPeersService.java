package io.macrosquare.research.application.service;

import io.macrosquare.research.application.port.in.QueryDynamicPeersUseCase;
import io.macrosquare.research.application.port.out.PeerTaxonomyRepository;
import io.macrosquare.research.domain.peer.PeerDiscoveryPolicy;
import io.macrosquare.research.domain.peer.PeerDiscoveryResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

public final class QueryDynamicPeersService implements QueryDynamicPeersUseCase {

    private final PeerTaxonomyRepository repository;
    private final PeerDiscoveryPolicy policy;
    private final Clock clock;

    public QueryDynamicPeersService(PeerTaxonomyRepository repository, PeerDiscoveryPolicy policy, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public PeerDiscoveryResult query(String ticker, LocalDate asOf, int limit) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var date = asOf == null ? LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC) : asOf;
        var target = repository.findAsOf(ticker.trim().toUpperCase(Locale.ROOT), date);
        var candidates = target == null ? java.util.List.<io.macrosquare.research.domain.peer.PeerTaxonomy>of()
                : repository.loadCandidates(target, date, 500);
        return policy.discover(target, candidates, date, limit);
    }
}
