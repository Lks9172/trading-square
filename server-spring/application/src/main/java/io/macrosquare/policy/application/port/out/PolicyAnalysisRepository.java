package io.macrosquare.policy.application.port.out;

import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;

import java.util.List;

public interface PolicyAnalysisRepository {
    int save(List<PolicyDocumentAnalysis> analyses);

    List<PolicyDocumentAnalysis> loadLatest(int limit);
}
