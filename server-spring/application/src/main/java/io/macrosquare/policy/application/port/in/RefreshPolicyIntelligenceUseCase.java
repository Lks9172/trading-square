package io.macrosquare.policy.application.port.in;

import io.macrosquare.policy.application.model.PolicyRefreshReport;

public interface RefreshPolicyIntelligenceUseCase {
    PolicyRefreshReport refresh();
}
