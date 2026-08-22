package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.port.in.CompanyResearchParityReport;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.CompanyFundamentalsFreshness;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import io.macrosquare.company.domain.model.Ticker;
import io.macrosquare.company.domain.service.CompanyBuyScoringPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsNormalizationPolicy;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CompanyResearchProjectionComposerFreshnessTest {

    @Test
    void staleFundamentalsRemainVisibleButCompositeScoresAreWithheld() {
        var financials = new CompanyFundamentalsNormalizationPolicy().normalize(
                new Ticker("TEST"), "0000000001", evidence(), 50.0, LocalDate.parse("2026-08-06"));
        var score = new CompanyScoringPolicy().evaluate(financials.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 2.0, 0.5);
        var buy = new CompanyBuyScoringPolicy().evaluate(financials.scoringFinancials(), score, expectations);
        var quote = new CompanyMarketQuote("TEST", 50.0, LocalDate.parse("2026-08-06"));
        var analyst = new CompanyAnalystConsensus(1.0, 10.0);
        var history = new CompanyAnalystHistoryRead(
                "TEST", List.of(
                        new CompanyAnalystHistoryPoint(LocalDate.parse("2026-08-05"), 0.9, 8.0),
                        new CompanyAnalystHistoryPoint(LocalDate.parse("2026-08-06"), 1.0, 10.0)
                ), CompanyAnalystHistoryRead.Mode.SEED_ONLY,
                CompanyAnalystHistoryRead.Source.SEED,
                CompanyAnalystHistoryRead.SourceState.AVAILABLE,
                CompanyAnalystHistoryRead.SourceState.NOT_EXPECTED,
                false, List.of(), 2, null, LocalDate.parse("2026-08-06"), null);
        var freshness = new CompanyFundamentalsFreshness(
                CompanyFundamentalsFreshness.Status.LAGGING,
                LocalDate.parse("2026-03-31"), LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-08-03"), "10-Q", 91,
                List.of("최신 10-Q보다 재무 계산 기준일이 뒤처짐"));
        var report = new CompanyResearchParityReport(
                "TEST", "0000000001", "0000000001",
                false, true, true, true, true, true, true, true, true, List.of(),
                quote, quote, analyst, analyst, history, expectations, expectations,
                financials, financials, score, score, buy, buy, freshness);

        var result = CompanyResearchProjectionComposer.core(
                CompanyRevenueMixComposerTest.research(true), report);

        assertInstanceOf(NullValue.class, result.score().fields().get("totalScore"));
        assertInstanceOf(NullValue.class, result.buyScore().fields().get("buyScore"));
        assertEquals("LAGGING", ((TextValue) result.financials().fields().get("fundamentalsStatus")).value());
        assertEquals("2026-06-30", ((TextValue) result.financials().fields()
                .get("latestPeriodicReportDate")).value());
        var upsideHistory = assertInstanceOf(
                ArrayValue.class, result.financials().fields().get("estimateUpsideHistory"));
        assertEquals(2, upsideHistory.values().size());
        var latest = assertInstanceOf(ObjectValue.class, upsideHistory.values().getLast());
        assertEquals("2026-08-06", ((TextValue) latest.fields().get("date")).value());
        assertEquals(10.0, ((NumberValue) latest.fields().get("value")).value().doubleValue());
    }

    @Test
    void failedCoreOrDecisionRefreshCannotRetainAPreviousBuyAction() {
        var source = CompanyRevenueMixComposerTest.research(true);
        var staleBuy = new io.macrosquare.company.application.model.CompanyReadModels.Research(
                source.profile(), source.quote(), source.financials(), source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(),
                source.bottleneck(), source.narrative(), source.capitalFlow(), source.cashFlowQuality(),
                source.multipleInsight(), source.guidanceInsight(), source.timeframeView(),
                source.correctionAssessment(), source.thesisMonitor(), source.reversalConfirmation(),
                source.sectorContext(), source.verdicts(), source.bottomSignal(),
                CompanyRevenueMixComposerTest.object("action", CompanyRevenueMixComposerTest.text("STRONG BUY")),
                CompanyRevenueMixComposerTest.object("action", CompanyRevenueMixComposerTest.text("STRONG BUY")),
                source.peers()
        );

        var noCore = CompanyResearchProjectionComposer.pendingCurrentCore(staleBuy);
        var noDecision = CompanyResearchProjectionComposer.pendingCurrentDecision(staleBuy);

        assertEquals("HOLD", ((TextValue) ((ObjectValue) noCore.positionSizing())
                .fields().get("action")).value());
        assertEquals("HOLD", ((TextValue) ((ObjectValue) noCore.executionBridge())
                .fields().get("companyAction")).value());
        assertEquals("HOLD", ((TextValue) ((ObjectValue) noDecision.positionSizing())
                .fields().get("action")).value());
        assertFalse(noDecision.verdicts() instanceof ObjectValue verdicts
                && verdicts.fields().containsKey("investmentDecision"));
    }

    private static CompanyFundamentalsEvidence evidence() {
        return new CompanyFundamentalsEvidence(
                List.of(annual("2026-03-31", 1_000), annual("2025-03-31", 900)),
                List.of(annual("2026-03-31", 200)),
                List.of(annual("2026-03-31", 150)),
                List.of(annual("2026-03-31", 180)),
                List.of(annual("2026-03-31", 30)),
                List.of(point("2026-03-31", 300)),
                List.of(point("2026-03-31", 100)),
                List.of(point("2026-03-31", 10)),
                List.of(), List.of(point("2026-03-31", 600)),
                List.of(point("2026-03-31", 400)),
                List.of(point("2026-03-31", 200)),
                List.of(), List.of()
        );
    }

    private static FinancialFactPoint annual(String end, double value) {
        return new FinancialFactPoint(value, "10-K", "FY", end);
    }

    private static FinancialFactPoint point(String end, double value) {
        return new FinancialFactPoint(value, null, null, end);
    }
}
