package io.macrosquare.policy.application.port.in;

import io.macrosquare.policy.domain.model.PolicyIntelligenceSnapshot;

public interface QueryPolicyIntelligenceUseCase {
    PolicyIntelligenceSnapshot query();
}
