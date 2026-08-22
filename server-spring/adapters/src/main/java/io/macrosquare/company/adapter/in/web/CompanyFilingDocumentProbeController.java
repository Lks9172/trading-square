package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.ProbeCompanyFilingDocumentUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration/company-filing-document-probe")
public final class CompanyFilingDocumentProbeController {

    private final ProbeCompanyFilingDocumentUseCase useCase;

    public CompanyFilingDocumentProbeController(ProbeCompanyFilingDocumentUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping
    public CompanyFilingDocumentProbeResponse probe(@RequestParam("url") String url) {
        return CompanyFilingDocumentProbeResponse.from(useCase.probe(url));
    }
}
