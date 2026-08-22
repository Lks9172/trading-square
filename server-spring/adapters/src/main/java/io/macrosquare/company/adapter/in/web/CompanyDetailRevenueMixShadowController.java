package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.EvaluateCompanyDetailRevenueMixShadowUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class CompanyDetailRevenueMixShadowController {

    private final EvaluateCompanyDetailRevenueMixShadowUseCase useCase;

    public CompanyDetailRevenueMixShadowController(EvaluateCompanyDetailRevenueMixShadowUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @GetMapping("/company-detail-revenue-mix-shadow/{ticker}")
    public CompanyDetailRevenueMixShadowResponse evaluate(@PathVariable String ticker) {
        return CompanyDetailRevenueMixShadowResponse.from(useCase.evaluate(ticker));
    }
}
