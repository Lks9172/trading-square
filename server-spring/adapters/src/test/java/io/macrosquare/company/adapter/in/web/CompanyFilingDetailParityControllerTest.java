package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.CompanyFilingDetailParityReport;
import io.macrosquare.company.domain.model.CompanyGuidanceAnalysis;
import io.macrosquare.company.domain.model.CompanyGuidanceMetric;
import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary;
import io.macrosquare.company.domain.model.CompanyIrMaterial;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyFilingDetailParityControllerTest {

    @Test
    void exposesCompatibilityAndIntentionalDiscoverySignalsSeparately() throws Exception {
        var material = new CompanyIrMaterial(
                "EX-99.1", "8-K", LocalDate.parse("2026-05-20"),
                "https://www.sec.gov/Archives/edgar/data/1/file.htm",
                CompanyIrMaterial.Type.EARNINGS_RELEASE,
                CompanyIrMaterial.Source.INDEX,
                CompanyIrMaterial.ContentType.HTML,
                "Revenue increased."
        );
        var guidanceSummary = new CompanyGuidanceSummary(
                CompanyGuidanceSummary.Stance.RAISED,
                new CompanyGuidanceMetric(
                        CompanyGuidanceMetric.Direction.RAISED,
                        "Revenue guidance was raised to 10% to 12%.",
                        new CompanyGuidanceMetricValue(
                                "10% to 12%", BigDecimal.TEN, new BigDecimal("12"),
                                CompanyGuidanceMetricValue.Unit.PERCENT
                        )
                ),
                null,
                null,
                null,
                List.of("Revenue guidance was raised to 10% to 12%.")
        );
        var guidance = CompanyGuidanceAnalysis.from(material, guidanceSummary);
        var report = new CompanyFilingDetailParityReport(
                "NVDA", "0001045810", "0001045810",
                100, 1, 1,
                true, true, true, false, true, true, true, true,
                0, 1, 1, 1, 0, 0, 0,
                1, 1, 1, 1, 1,
                List.of("0001045810-26-000051"),
                List.of(), List.of(), List.of(),
                List.of(), List.of(material), List.of(guidance)
        );
        var mvc = MockMvcBuilders.standaloneSetup(new CompanyFilingDetailParityController(ticker -> report)).build();

        mvc.perform(get("/internal/v1/migration/company-filing-detail-parity/nvda"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("NVDA"))
                .andExpect(jsonPath("$.migrationReady").value(true))
                .andExpect(jsonPath("$.exactLegacyMatch").value(false))
                .andExpect(jsonPath("$.directDiscoveryImprovement").value(true))
                .andExpect(jsonPath("$.pdfExtractionCoveragePassed").value(true))
                .andExpect(jsonPath("$.guidanceExtractionCoveragePassed").value(true))
                .andExpect(jsonPath("$.structuredGuidanceMetricCount").value(1))
                .andExpect(jsonPath("$.guidance[0].summary.stance").value("raised"))
                .andExpect(jsonPath("$.guidance[0].summary.revenue.direction").value("raised"))
                .andExpect(jsonPath("$.guidance[0].summary.revenue.value.min").value(10))
                .andExpect(jsonPath("$.guidance[0].summary.revenue.value.max").value(12))
                .andExpect(jsonPath("$.guidance[0].summary.revenue.value.unit").value("percent"))
                .andExpect(jsonPath("$.result.spring[0].type").value("earnings-release"))
                .andExpect(jsonPath("$.result.spring[0].source").value("index"));
    }
}
