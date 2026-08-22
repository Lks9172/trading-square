package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.application.port.in.CompanyRevenueMixParityReport;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;
import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEntry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyRevenueMixParityControllerTest {

    @Test
    void exposesBoundedActualValuesAndPercentagesWithoutRawFilingMarkup() throws Exception {
        var breakdown = new CompanyRevenueMixBreakdown(
                CompanyRevenueMixBreakdown.Category.SEGMENT,
                CompanyRevenueMixDimension.REPORTABLE_SEGMENT,
                "Statement Business Segments",
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-12-31"),
                "USD",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                new BigDecimal("100.0"),
                "https://www.sec.gov/Archives/edgar/data/1/annual.htm",
                List.of(
                        new CompanyRevenueMixEntry("Cloud", BigDecimal.valueOf(60), new BigDecimal("60.0")),
                        new CompanyRevenueMixEntry("Consumer", BigDecimal.valueOf(40), new BigDecimal("40.0"))
                )
        );
        var analysis = new CompanyRevenueMixAnalysis(breakdown, null, 1, 2);
        var report = new CompanyRevenueMixParityReport(
                "TEST", "0000000001", "0000000001",
                10, 1, 1, 2,
                true, true, true, true, true, false,
                List.of("0000000001-25-000001"), List.of(), List.of(),
                new CompanyRevenueMixLegacyRead(null, List.of(), List.of()),
                analysis
        );
        var mvc = MockMvcBuilders.standaloneSetup(
                new CompanyRevenueMixParityController(ignored -> report)
        ).build();

        mvc.perform(get("/internal/v1/migration/company-revenue-mix-parity/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationReady").value(true))
                .andExpect(jsonPath("$.result.spring.segment.periodEnd").value("2025-12-31"))
                .andExpect(jsonPath("$.result.spring.segment.entries[0].label").value("Cloud"))
                .andExpect(jsonPath("$.result.spring.segment.entries[0].percentOfTotal").value(60.0))
                .andExpect(jsonPath("$.result.spring.segment.source").isString())
                .andExpect(jsonPath("$.rawHtml").doesNotExist());
    }
}
