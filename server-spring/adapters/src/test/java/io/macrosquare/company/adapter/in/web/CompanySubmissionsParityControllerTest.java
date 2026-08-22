package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanySubmissionsSnapshot;
import io.macrosquare.company.application.port.in.CompanySubmissionsParityReport;
import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanySubmissionsParityControllerTest {

    @Test
    void exposesDirectSecProfileFilingMetadataAndEveryParityGate() throws Exception {
        var snapshot = new CompanySubmissionsSnapshot(
                new CompanySubmissionsSnapshot.Profile(
                        "NVDA", "0001045810", "NVIDIA CORP", "Nasdaq", "3674"
                ),
                List.of(new CompanySubmissionsSnapshot.Filing(
                        "0001045810-26-000060",
                        LocalDate.parse("2026-07-02"),
                        "8-K",
                        "nvda-20260628.htm",
                        "8-K",
                        false,
                        "https://www.sec.gov/Archives/edgar/data/1045810/000104581026000060/nvda-20260628.htm"
                ))
        );
        var report = new CompanySubmissionsParityReport(
                "NVDA", "0001045810", "0001045810", List.of("0001045810"),
                true, true, true, 1, 20, 0, List.of(), snapshot, snapshot
        );
        var mvc = MockMvcBuilders.standaloneSetup(
                new CompanySubmissionsParityController(ticker -> report)
        ).build();

        mvc.perform(get("/internal/v1/migration/company-submissions-parity/nvda"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("NVDA"))
                .andExpect(jsonPath("$.registryCik").value("0001045810"))
                .andExpect(jsonPath("$.selectedCik").value("0001045810"))
                .andExpect(jsonPath("$.allMatched").value(true))
                .andExpect(jsonPath("$.profileMatched").value(true))
                .andExpect(jsonPath("$.filingsMatched").value(true))
                .andExpect(jsonPath("$.directAvailableFilingCount").value(20))
                .andExpect(jsonPath("$.result.spring.profile.name").value("NVIDIA CORP"))
                .andExpect(jsonPath("$.result.spring.filings[0].form").value("8-K"))
                .andExpect(jsonPath("$.result.spring.filings[0].isEarningsRelated").value(false));
    }

    @Test
    void hidesSecFailureDetailsBehindTheSafeBadGateway() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                        new CompanySubmissionsParityController(ticker -> {
                            throw new CompanySubmissionsUnavailableException("SEC internal details");
                        })
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/company-submissions-parity/NVDA"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Company research parity data is temporarily unavailable"));
    }
}
