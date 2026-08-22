package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.model.WeeklyReviewReport;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment.SourceUnit;
import io.macrosquare.execution.domain.model.PortfolioDriftAssessment;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeeklyReviewControllerTest {

    @Test
    void servesTheSameCurrentReviewAsJsonAndTelegramText() throws Exception {
        var holdings = new PortfolioAllocationAssessment(
                SourceUnit.PERCENT, Map.of("cash", 20d), Map.of("cash", 20d),
                20, 100, 20, 80, 0, false, List.of());
        var report = new WeeklyReviewReport(
                Instant.parse("2026-08-05T12:00:00Z"),
                LocalDate.parse("2026-07-30"), LocalDate.parse("2026-08-05"),
                "RISK_ON", 72,
                List.of(new WeeklyReviewReport.SignalReview("NASDAQ", "BUY", "5/7", 100)),
                List.of("[NASDAQ BUY] 추세 유지"), List.of(), List.of(), List.of(),
                holdings, new PortfolioDriftAssessment(0, List.of()), "CURRENT WEEKLY"
        );
        var mvc = MockMvcBuilders.standaloneSetup(new WeeklyReviewController(() -> report)).build();

        mvc.perform(get("/api/weekly-report"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"report":{"generatedAt":"2026-08-05T12:00:00Z",
                        "period":{"from":"2026-07-30","to":"2026-08-05"},
                        "regime":{"current":"RISK_ON","score":72},
                        "keySignals":[{"asset":"NASDAQ","signal":"BUY","met":"5/7","dataCoveragePct":100}],
                        "topReasons":["[NASDAQ BUY] 추세 유지"],"warnings":[],"nextEvents":[],
                        "ruleViolations":[],"portfolio":{"sourceUnit":"PERCENT","normalized":false,
                        "percentages":{"cash":20.0},"sourceValues":{"cash":20.0},"denominator":100.0,
                        "allocatedPct":20.0,"unallocatedPct":80.0,"overAllocatedPct":0.0,"totalDriftPct":0.0}},
                        "text":"CURRENT WEEKLY"}
                        """, JsonCompareMode.STRICT));

        mvc.perform(get("/api/weekly-report").queryParam("format", "text"))
                .andExpect(status().isOk())
                .andExpect(content().string("CURRENT WEEKLY"));
    }
}
