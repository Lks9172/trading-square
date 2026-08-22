package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class CompanyResearchParityController {

    private final EvaluateCompanyResearchParityUseCase parityUseCase;

    public CompanyResearchParityController(EvaluateCompanyResearchParityUseCase parityUseCase) {
        this.parityUseCase = Objects.requireNonNull(parityUseCase);
    }

    @GetMapping("/company-research-parity/{ticker}")
    public CompanyResearchParityResponse evaluate(@PathVariable String ticker) {
        return CompanyResearchParityResponse.from(parityUseCase.evaluate(ticker));
    }
}
