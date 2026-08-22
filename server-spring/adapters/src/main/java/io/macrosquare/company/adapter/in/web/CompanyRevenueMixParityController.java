package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.EvaluateCompanyRevenueMixParityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class CompanyRevenueMixParityController {

    private final EvaluateCompanyRevenueMixParityUseCase useCase;

    public CompanyRevenueMixParityController(EvaluateCompanyRevenueMixParityUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @GetMapping("/company-revenue-mix-parity/{ticker}")
    public CompanyRevenueMixParityResponse evaluate(@PathVariable String ticker) {
        return CompanyRevenueMixParityResponse.from(useCase.evaluate(ticker));
    }
}
