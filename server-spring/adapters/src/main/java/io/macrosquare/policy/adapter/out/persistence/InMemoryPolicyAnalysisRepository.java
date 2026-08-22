package io.macrosquare.policy.adapter.out.persistence;

import io.macrosquare.policy.application.port.out.PolicyAnalysisRepository;
import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPolicyAnalysisRepository implements PolicyAnalysisRepository {

    private final ConcurrentHashMap<String, PolicyDocumentAnalysis> analyses = new ConcurrentHashMap<>();

    @Override
    public int save(List<PolicyDocumentAnalysis> values) {
        values.forEach(value -> analyses.put(value.document().id(), value));
        return values.size();
    }

    @Override
    public List<PolicyDocumentAnalysis> loadLatest(int limit) {
        return analyses.values().stream()
                .sorted(Comparator.comparing((PolicyDocumentAnalysis value) -> value.document().publishedAt()).reversed())
                .limit(limit)
                .toList();
    }
}
