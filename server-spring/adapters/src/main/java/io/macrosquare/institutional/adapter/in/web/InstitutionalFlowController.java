package io.macrosquare.institutional.adapter.in.web;

import io.macrosquare.institutional.application.port.in.QueryInstitutionalFlowsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
public final class InstitutionalFlowController {

    private final QueryInstitutionalFlowsUseCase query;

    public InstitutionalFlowController(QueryInstitutionalFlowsUseCase query) {
        this.query = Objects.requireNonNull(query);
    }

    @GetMapping("/api/institutional-flows")
    public InstitutionalFlowResponse flows() {
        return InstitutionalFlowResponse.from(query.query());
    }
}
