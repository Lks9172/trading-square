package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.CompanyResearchParityReport;
import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFundamentalsUnavailableException;
import io.macrosquare.company.application.port.out.CompanyMarketQuoteUnavailableException;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.CompanyBuyLabel;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyFundamentalsFreshness;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;
import io.macrosquare.company.domain.model.Ticker;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyResearchParityControllerTest {

    @Test
    void exposesTheReadOnlyCompanyNormalizationAndScoreParallelRun() throws Exception {
        var snapshot = snapshot();
        var score = new CompanyScore(
                new Ticker("TEST"),
                87,
                new ScoreBreakdown(90, List.of("growth")),
                new ScoreBreakdown(87, List.of("quality")),
                new ScoreBreakdown(85, List.of("valuation")),
                new ScoreBreakdown(84, List.of("balance")),
                List.of("growth", "quality", "valuation", "balance")
        );
        var buyScore = new CompanyBuyScore(78, 36, 74, CompanyBuyLabel.FAVORABLE, List.of("quality"));
        var quote = new CompanyMarketQuote("TEST", 50.0, LocalDate.parse("2026-07-17"));
        var analystConsensus = new CompanyAnalystConsensus(1.0, 10.0);
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var historyPoints = List.of(new CompanyAnalystHistoryPoint(
                LocalDate.parse("2026-06-19"), 0.5, 5.0
        ));
        var analystHistory = new CompanyAnalystHistoryRead(
                "TEST",
                historyPoints,
                CompanyAnalystHistoryRead.Mode.DUAL_COMPARE,
                CompanyAnalystHistoryRead.Source.SEED,
                CompanyAnalystHistoryRead.SourceState.AVAILABLE,
                CompanyAnalystHistoryRead.SourceState.AVAILABLE,
                true,
                List.of(),
                1,
                1,
                LocalDate.parse("2026-06-19"),
                LocalDate.parse("2026-06-19")
        );
        var report = new CompanyResearchParityReport(
                "TEST", "0000000001", "0000000001", true, true, true, true, true, true, true, true, true, List.of(),
                quote, quote, analystConsensus, analystConsensus, analystHistory, expectations, expectations,
                snapshot, snapshot, score, score, buyScore, buyScore,
                new CompanyFundamentalsFreshness(
                        CompanyFundamentalsFreshness.Status.CURRENT,
                        LocalDate.parse("2025-12-31"), LocalDate.parse("2025-12-31"),
                        LocalDate.parse("2026-02-01"), "10-K", 0, List.of())
        );
        var mvc = MockMvcBuilders.standaloneSetup(
                new CompanyResearchParityController(ticker -> report)
        ).build();

        mvc.perform(get("/internal/v1/migration/company-research-parity/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("TEST"))
                .andExpect(jsonPath("$.allMatched").value(true))
                .andExpect(jsonPath("$.registryCik").value("0000000001"))
                .andExpect(jsonPath("$.identityMatched").value(true))
                .andExpect(jsonPath("$.quoteMatched").value(true))
                .andExpect(jsonPath("$.quote.spring.price").value(50.0))
                .andExpect(jsonPath("$.analystConsensusMatched").value(true))
                .andExpect(jsonPath("$.analystConsensus.spring.analystScore").value(1.0))
                .andExpect(jsonPath("$.analystHistoryMatched").value(true))
                .andExpect(jsonPath("$.analystHistory.mode").value("DUAL_COMPARE"))
                .andExpect(jsonPath("$.analystHistory.selectedSource").value("LEGACY"))
                .andExpect(jsonPath("$.analystHistory.comparisonPerformed").value(true))
                .andExpect(jsonPath("$.analystHistory.shadowPointCount").value(1))
                .andExpect(jsonPath("$.expectationsMatched").value(true))
                .andExpect(jsonPath("$.expectations.spring.estimateRevision30d").doesNotExist())
                .andExpect(jsonPath("$.expectations.spring.targetUpsideChange30d").value(5.0))
                .andExpect(jsonPath("$.fundamentals.spring.revenueTtm").value(500))
                .andExpect(jsonPath("$.fundamentalsFreshness.status").value("CURRENT"))
                .andExpect(jsonPath("$.fundamentalsFreshness.scoreComparable").value(true))
                .andExpect(jsonPath("$.score.legacy.totalScore").value(87))
                .andExpect(jsonPath("$.buyScore.spring.label").value("매수 우호"));
    }

    @Test
    void hidesYahooFailureDetailsBehindTheSameSafeBadGateway() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CompanyResearchParityController(ticker -> {
                    throw new CompanyMarketQuoteUnavailableException("Yahoo internal details");
                }))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/company-research-parity/NVDA"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Company research parity data is temporarily unavailable"));
    }

    @Test
    void hidesSecFailureDetailsBehindASafeBadGateway() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CompanyResearchParityController(ticker -> {
                    throw new CompanyFundamentalsUnavailableException("SEC internal details");
                }))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/company-research-parity/NVDA"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Company research parity data is temporarily unavailable"));
    }

    @Test
    void hidesAnalystEvidenceFailureDetailsBehindTheSameSafeBadGateway() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CompanyResearchParityController(ticker -> {
                    throw new CompanyAnalystEvidenceUnavailableException("filesystem details");
                }))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/company-research-parity/NVDA"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Company research parity data is temporarily unavailable"));
    }

    private static CompanyFundamentalsSnapshot snapshot() {
        return new CompanyFundamentalsSnapshot(
                new Ticker("TEST"), "0000000001", "2025-12-31",
                500.0, 125.0, 92.0, 94.0, 150.0, 90.0, 210.0, 120.0,
                75.0, 30.0, 35.0, 129.0, 10.0, 500.0, 440.0,
                25.0, 25.0, null, 18.8, -0.12, 0.88, 4.68,
                null, null, 18.4, 1.75, 0.15, 0.06
        );
    }
}
