package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.CompanyFilingDocumentProbeReport;
import io.macrosquare.company.application.port.out.CompanyFilingDocumentUnavailableException;
import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyFilingDocumentProbeControllerTest {

    private static final String URL = "https://www.sec.gov/Archives/edgar/data/8670/deck.pdf";

    @Test
    void exposesPdfExtractionDiagnosticsAndPreview() throws Exception {
        var report = new CompanyFilingDocumentProbeReport(
                URL,
                CompanyFilingDocumentContent.Format.PDF,
                80,
                80,
                12_345,
                true,
                false,
                "INVESTOR DAY 2025",
                "Revenue guidance was raised."
        );
        var mvc = MockMvcBuilders.standaloneSetup(
                new CompanyFilingDocumentProbeController(ignored -> report)
        ).build();

        mvc.perform(get("/internal/v1/migration/company-filing-document-probe").param("url", URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("pdf"))
                .andExpect(jsonPath("$.totalPages").value(80))
                .andExpect(jsonPath("$.textCharacters").value(12_345))
                .andExpect(jsonPath("$.preview").value("INVESTOR DAY 2025"));
    }

    @Test
    void mapsExtractionFailuresToBoundedBadGatewayResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                        new CompanyFilingDocumentProbeController(ignored -> {
                            throw new CompanyFilingDocumentUnavailableException("sensitive upstream detail");
                        })
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/company-filing-document-probe").param("url", URL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Company research parity data is temporarily unavailable"));
    }
}
