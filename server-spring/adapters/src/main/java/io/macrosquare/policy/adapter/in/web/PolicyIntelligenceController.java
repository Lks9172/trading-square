package io.macrosquare.policy.adapter.in.web;

import io.macrosquare.policy.application.port.in.QueryPolicyIntelligenceUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
public final class PolicyIntelligenceController {

    private final QueryPolicyIntelligenceUseCase query;

    public PolicyIntelligenceController(QueryPolicyIntelligenceUseCase query) {
        this.query = Objects.requireNonNull(query);
    }

    @GetMapping("/api/policy-intelligence")
    public PolicyIntelligenceResponse intelligence() {
        return PolicyIntelligenceResponse.from(query.query());
    }
}
