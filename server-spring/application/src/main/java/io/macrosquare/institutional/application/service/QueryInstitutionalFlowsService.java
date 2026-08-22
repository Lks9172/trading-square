package io.macrosquare.institutional.application.service;

import io.macrosquare.institutional.application.port.in.QueryInstitutionalFlowsUseCase;
import io.macrosquare.institutional.application.port.out.InstitutionalFilingRepository;
import io.macrosquare.institutional.application.port.out.InstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.application.port.out.LoadInstitutionalAnalystScorePort;
import io.macrosquare.institutional.domain.model.InstitutionalFlowSnapshot;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;
import io.macrosquare.institutional.domain.service.InstitutionalFlowPolicy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QueryInstitutionalFlowsService implements QueryInstitutionalFlowsUseCase {

    private static final int MAX_ANALYST_LOOKUPS = 60;

    private final InstitutionalFilingRepository repository;
    private final InstitutionalSecurityIdentityRepository identityRepository;
    private final LoadInstitutionalAnalystScorePort analystScores;
    private final InstitutionalFlowPolicy policy;

    public QueryInstitutionalFlowsService(
            InstitutionalFilingRepository repository,
            InstitutionalFlowPolicy policy
    ) {
        this(repository, null, ticker -> null, policy);
    }

    public QueryInstitutionalFlowsService(
            InstitutionalFilingRepository repository,
            InstitutionalSecurityIdentityRepository identityRepository,
            LoadInstitutionalAnalystScorePort analystScores,
            InstitutionalFlowPolicy policy
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.identityRepository = identityRepository;
        this.analystScores = Objects.requireNonNull(analystScores);
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public InstitutionalFlowSnapshot query() {
        var filings = repository.loadLatestPerManager(2);
        if (filings.isEmpty()) return policy.evaluate(List.of());
        var reportPeriod = filings.stream().map(value -> value.reportPeriod()).max(java.time.LocalDate::compareTo)
                .orElseThrow();
        var identities = identityRepository == null ? Map.<String, InstitutionalSecurityIdentity>of()
                : identityRepository.loadActiveOn(reportPeriod);
        var base = policy.evaluate(filings, identities, Map.of());
        var candidates = new LinkedHashSet<String>();
        base.consensus().forEach(value -> add(candidates, value.identity()));
        base.managers().forEach(manager -> {
            manager.topBuys().forEach(value -> add(candidates, value.identity()));
            manager.topSells().forEach(value -> add(candidates, value.identity()));
        });
        var scores = new LinkedHashMap<String, Double>();
        candidates.stream().limit(MAX_ANALYST_LOOKUPS).forEach(ticker -> {
            try {
                var score = analystScores.load(ticker);
                if (score != null && Double.isFinite(score)) scores.put(ticker, score);
            } catch (RuntimeException ignored) {
                // Analyst evidence is optional; 13F money-flow remains available independently.
            }
        });
        return policy.evaluate(filings, identities, scores);
    }

    private static void add(java.util.Set<String> target, InstitutionalSecurityIdentity identity) {
        if (identity != null) target.add(identity.ticker());
    }
}
