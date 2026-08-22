package io.macrosquare.policy.adapter.out.official;

import io.macrosquare.policy.application.port.out.CollectPolicyDocumentsPort;
import io.macrosquare.policy.domain.model.PolicyDocument;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Failure-isolated composition of official policy agencies. */
public final class CompositePolicyDocumentAdapter implements CollectPolicyDocumentsPort {

    private final List<CollectPolicyDocumentsPort> collectors;

    public CompositePolicyDocumentAdapter(List<CollectPolicyDocumentsPort> collectors) {
        this.collectors = List.copyOf(collectors);
        if (this.collectors.isEmpty()) throw new IllegalArgumentException("at least one policy collector is required");
    }

    @Override
    public List<PolicyDocument> collect(int maximumDocuments) {
        var documents = new LinkedHashMap<String, PolicyDocument>();
        RuntimeException totalFailure = null;
        for (var collector : collectors) {
            try {
                collector.collect(maximumDocuments).forEach(value -> documents.putIfAbsent(value.id(), value));
            } catch (RuntimeException error) {
                totalFailure = error;
            }
        }
        if (documents.isEmpty() && totalFailure != null) throw totalFailure;
        return documents.values().stream()
                .sorted(Comparator.comparing(PolicyDocument::publishedAt).reversed()
                        .thenComparing(PolicyDocument::id))
                .limit(maximumDocuments).toList();
    }
}
