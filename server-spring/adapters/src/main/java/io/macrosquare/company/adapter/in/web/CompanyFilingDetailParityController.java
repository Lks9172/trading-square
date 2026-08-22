package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.EvaluateCompanyFilingDetailParityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class CompanyFilingDetailParityController {

    private final EvaluateCompanyFilingDetailParityUseCase parityUseCase;

    public CompanyFilingDetailParityController(EvaluateCompanyFilingDetailParityUseCase parityUseCase) {
        this.parityUseCase = Objects.requireNonNull(parityUseCase);
    }

    @GetMapping("/company-filing-detail-parity/{ticker}")
    public CompanyFilingDetailParityResponse evaluate(@PathVariable String ticker) {
        return CompanyFilingDetailParityResponse.from(parityUseCase.evaluate(ticker));
    }
}
