package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration")
public final class CompanyPriceSignalParityController {

    private final EvaluateCompanyPriceSignalParityUseCase parityUseCase;

    public CompanyPriceSignalParityController(EvaluateCompanyPriceSignalParityUseCase parityUseCase) {
        this.parityUseCase = Objects.requireNonNull(parityUseCase);
    }

    @GetMapping("/company-price-signal-parity/{ticker}")
    public CompanyPriceSignalParityResponse evaluate(@PathVariable String ticker) {
        return CompanyPriceSignalParityResponse.from(parityUseCase.evaluate(ticker));
    }
}
