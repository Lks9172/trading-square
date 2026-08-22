package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.EvaluateCompanySubmissionsParityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class CompanySubmissionsParityController {

    private final EvaluateCompanySubmissionsParityUseCase parityUseCase;

    public CompanySubmissionsParityController(EvaluateCompanySubmissionsParityUseCase parityUseCase) {
        this.parityUseCase = Objects.requireNonNull(parityUseCase);
    }

    @GetMapping("/company-submissions-parity/{ticker}")
    public CompanySubmissionsParityResponse evaluate(@PathVariable String ticker) {
        return CompanySubmissionsParityResponse.from(parityUseCase.evaluate(ticker));
    }
}
