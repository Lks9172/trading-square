package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import io.macrosquare.company.domain.model.ScoreBreakdown;
import io.macrosquare.company.domain.model.Ticker;
import io.macrosquare.company.domain.service.CompanyBuyScoringPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsNormalizationPolicy;
import io.macrosquare.company.domain.service.CompanyMarketExpectationsPolicy;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCompanyResearchParityServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC);
    private final CompanyFundamentalsNormalizationPolicy normalization = new CompanyFundamentalsNormalizationPolicy();
    private final CompanyMarketExpectationsPolicy expectationsPolicy = new CompanyMarketExpectationsPolicy();
    private final CompanyScoringPolicy scoring = new CompanyScoringPolicy();
    private final CompanyBuyScoringPolicy buyScoring = new CompanyBuyScoringPolicy();

    @Test
    void dualRunsDirectFundamentalsAndScoresAgainstTheLegacyCompanyProjection() {
        var evidence = sampleEvidence();
        var fundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(fundamentals.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(fundamentals.scoringFinancials(), score, expectations);
        var research = research(fundamentals, score, buyScore, expectations, 50.0);
        var observedTicker = new AtomicReference<String>();
        var observedIdentityTicker = new AtomicReference<String>();
        var observedCik = new AtomicReference<String>();
        var observedQuoteTicker = new AtomicReference<String>();
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(research, observedTicker),
                ticker -> {
                    observedIdentityTicker.set(ticker);
                    return new CompanyIdentity("TEST", "0000000001", "Test Company");
                },
                cik -> {
                    observedCik.set(cik);
                    return evidence;
                },
                ticker -> {
                    observedQuoteTicker.set(ticker);
                    return quote(50.0);
                },
                ticker -> analystConsensus(expectations),
                ticker -> analystHistory(expectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("  test  ");

        assertTrue(report.allMatched());
        assertTrue(report.identityMatched());
        assertTrue(report.quoteMatched());
        assertTrue(report.analystConsensusMatched());
        assertTrue(report.analystHistoryMatched());
        assertEquals(CompanyAnalystHistoryRead.Mode.SEED_ONLY, report.analystHistory().mode());
        assertTrue(report.expectationsMatched());
        assertTrue(report.fundamentalsMatched());
        assertTrue(report.scoreMatched());
        assertTrue(report.buyScoreMatched());
        assertTrue(report.differences().isEmpty());
        assertEquals("TEST", observedTicker.get());
        assertEquals("TEST", observedIdentityTicker.get());
        assertEquals("0000000001", observedCik.get());
        assertEquals("TEST", observedQuoteTicker.get());
        assertEquals(85, report.springScore().totalScore());
        assertEquals(76, report.springBuyScore().buyScore());
    }

    @Test
    void reportsFieldLevelDifferencesWithoutReplacingTheLegacyServingResult() {
        var evidence = sampleEvidence();
        var actual = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(actual.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(actual.scoringFinancials(), score, expectations);
        var stale = copyWithMarketCap(actual, actual.marketCap() + 1_000);
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(research(stale, score, buyScore, expectations, 50.0), new AtomicReference<>()),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> evidence,
                ticker -> quote(50.0),
                ticker -> analystConsensus(expectations),
                ticker -> analystHistory(expectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertTrue(!report.allMatched());
        assertTrue(!report.fundamentalsMatched());
        assertTrue(report.scoreMatched());
        assertTrue(report.buyScoreMatched());
        assertEquals(List.of("fundamentals.marketCap"), report.differences());
        assertEquals(stale.marketCap(), report.legacyFundamentals().marketCap());
    }

    @Test
    void rejectsAQuoteForADifferentSecurityBeforeAnyScoreIsCalculated() {
        var evidence = sampleEvidence();
        var fundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(fundamentals.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(fundamentals.scoringFinancials(), score, expectations);
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(fundamentals, score, buyScore, expectations, 50.0),
                        new AtomicReference<>()),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> evidence,
                ticker -> new CompanyMarketQuote("WRONG", 50.0, LocalDate.parse("2026-07-17")),
                ticker -> analystConsensus(expectations),
                ticker -> analystHistory(expectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var error = assertThrows(CompanyResearchParityUnavailableException.class,
                () -> service.evaluate("TEST"));

        assertTrue(error.getMessage().contains("different security"));
    }

    @Test
    void rejectsAStaleOrFutureDatedQuoteBeforeAnyScoreIsCalculated() {
        var evidence = sampleEvidence();
        var fundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(fundamentals.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(fundamentals.scoringFinancials(), score, expectations);
        for (var invalidDate : List.of(
                LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-07-21")
        )) {
            var service = new EvaluateCompanyResearchParityService(
                    new StubCompanyReadPort(
                            research(fundamentals, score, buyScore, expectations, 50.0),
                            new AtomicReference<>()
                    ),
                    ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                    cik -> evidence,
                    ticker -> new CompanyMarketQuote("TEST", 50.0, invalidDate),
                    ticker -> analystConsensus(expectations),
                    ticker -> analystHistory(expectations),
                    normalization,
                    expectationsPolicy,
                    scoring,
                    buyScoring,
                    CLOCK
            );

            var error = assertThrows(CompanyResearchParityUnavailableException.class,
                    () -> service.evaluate("TEST"));

            assertTrue(error.getMessage().contains("freshness window"));
        }
    }

    @Test
    void keepsCurrentCoreAvailableWhenTheLegacyProjectionLacksRequiredInputs() {
        var malformed = new Research(
                object(Map.of("ticker", text("TEST"), "cik", text("0000000001"))),
                object(Map.of("price", number(50))),
                object(Map.of()),
                object(Map.of()),
                object(Map.of()),
                array(), array(), array(), NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, array()
        );
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(malformed, new AtomicReference<>()),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> sampleEvidence(),
                ticker -> quote(50.0),
                ticker -> analystConsensus(new CompanyMarketExpectations(null, null, null)),
                ticker -> analystHistory(new CompanyMarketExpectations(null, null, null)),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertFalse(report.legacyAvailable());
        assertFalse(report.allMatched());
        assertTrue(report.differences().stream()
                .anyMatch(value -> value.startsWith("legacyProjection.unavailable")));
        assertEquals("TEST", report.springQuote().symbol());
        assertEquals(500.0, report.springFundamentals().marketCap());
        assertEquals(85, report.springScore().totalScore());
    }

    @Test
    void usesTheDirectSecIdentityForFactsAndReportsLegacyCikDrift() {
        var evidence = sampleEvidence();
        var legacyFundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(legacyFundamentals.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(legacyFundamentals.scoringFinancials(), score, expectations);
        var observedCik = new AtomicReference<String>();
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(legacyFundamentals, score, buyScore, expectations, 50.0),
                        new AtomicReference<>()
                ),
                ticker -> new CompanyIdentity("TEST", "0000000002", "Test Company"),
                cik -> {
                    observedCik.set(cik);
                    return evidence;
                },
                ticker -> quote(50.0),
                ticker -> analystConsensus(expectations),
                ticker -> analystHistory(expectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertEquals("0000000002", observedCik.get());
        assertEquals("0000000002", report.cik());
        assertTrue(!report.identityMatched());
        assertTrue(!report.fundamentalsMatched());
        assertEquals(List.of("identity.cik", "fundamentals.cik"), report.differences());
    }

    @Test
    void mergesPredecessorHistoryWhenTheSuccessorPublishesANewerInterimStatement() {
        var evidence = sampleEvidence();
        var legacyFundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(legacyFundamentals.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(legacyFundamentals.scoringFinancials(), score, expectations);
        var observedCiks = new ArrayList<String>();
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(legacyFundamentals, score, buyScore, expectations, 50.0),
                        new AtomicReference<>()
                ),
                ticker -> new CompanyIdentity(
                        "TEST", "0000000002", "Successor Test Company",
                        List.of("0000000002", "0000000001")
                ),
                cik -> {
                    observedCiks.add(cik);
                    return cik.equals("0000000002") ? sparseSuccessorEvidence() : evidence;
                },
                ticker -> quote(50.0),
                ticker -> analystConsensus(expectations),
                ticker -> analystHistory(expectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertEquals(List.of("0000000002", "0000000001"), observedCiks);
        assertEquals("0000000002", report.registryCik());
        assertEquals("0000000002", report.cik());
        assertEquals("2026-06-30", report.springFundamentals().asOf());
        assertEquals(530.0, report.springFundamentals().revenueTtm());
        assertTrue(!report.identityMatched());
        assertTrue(!report.allMatched());
    }

    @Test
    void usesTheDirectYahooPriceAndReportsLegacyQuoteDriftSeparately() {
        var evidence = sampleEvidence();
        var legacyFundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(legacyFundamentals.scoringFinancials());
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var buyScore = buyScoring.evaluate(legacyFundamentals.scoringFinancials(), score, expectations);
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(legacyFundamentals, score, buyScore, expectations, 50.0),
                        new AtomicReference<>()
                ),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> evidence,
                ticker -> quote(55.0),
                ticker -> analystConsensus(expectations),
                ticker -> analystHistory(expectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertTrue(!report.allMatched());
        assertTrue(!report.quoteMatched());
        assertEquals(50.0, report.legacyQuote().price());
        assertEquals(55.0, report.springQuote().price());
        assertEquals(500.0, report.legacyFundamentals().marketCap());
        assertEquals(550.0, report.springFundamentals().marketCap());
        assertTrue(report.differences().contains("quote.price"));
        assertTrue(report.differences().contains("fundamentals.marketCap"));
    }

    @Test
    void usesDirectPersistedExpectationsAndReportsTheirDriftSeparately() {
        var evidence = sampleEvidence();
        var fundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var legacyExpectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var directExpectations = new CompanyMarketExpectations(10.0, 5.01, 0.5);
        var score = scoring.evaluate(fundamentals.scoringFinancials());
        var buyScore = buyScoring.evaluate(fundamentals.scoringFinancials(), score, legacyExpectations);
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(fundamentals, score, buyScore, legacyExpectations, 50.0),
                        new AtomicReference<>()
                ),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> evidence,
                ticker -> quote(50.0),
                ticker -> analystConsensus(directExpectations),
                ticker -> analystHistory(directExpectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertTrue(!report.allMatched());
        assertTrue(!report.expectationsMatched());
        assertTrue(report.quoteMatched());
        assertTrue(report.fundamentalsMatched());
        assertTrue(report.scoreMatched());
        assertEquals(5.0, report.legacyExpectations().targetUpsideChange30d());
        assertEquals(5.01, report.springExpectations().targetUpsideChange30d());
        assertTrue(report.differences().contains("expectations.targetUpsideChange30d"));
    }

    @Test
    void reportsAnalystHistorySourceDriftEvenWhenDerivedExpectationsStillMatch() {
        var evidence = sampleEvidence();
        var expectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var fundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(fundamentals.scoringFinancials());
        var buyScore = buyScoring.evaluate(fundamentals.scoringFinancials(), score, expectations);
        var baseHistory = analystHistory(expectations);
        var driftedHistory = new CompanyAnalystHistoryRead(
                "TEST",
                baseHistory.history(),
                CompanyAnalystHistoryRead.Mode.STORE_PREFERRED,
                CompanyAnalystHistoryRead.Source.STORE,
                CompanyAnalystHistoryRead.SourceState.AVAILABLE,
                CompanyAnalystHistoryRead.SourceState.AVAILABLE,
                true,
                List.of("analystHistory.points"),
                baseHistory.history().size(),
                baseHistory.history().size(),
                baseHistory.seedLatestDate(),
                baseHistory.seedLatestDate()
        );
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(fundamentals, score, buyScore, expectations, 50.0),
                        new AtomicReference<>()
                ),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> evidence,
                ticker -> quote(50.0),
                ticker -> analystConsensus(expectations),
                ticker -> driftedHistory,
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertTrue(!report.allMatched());
        assertTrue(!report.analystHistoryMatched());
        assertTrue(report.expectationsMatched());
        assertTrue(report.buyScoreMatched());
        assertEquals(List.of("analystHistory.points"), report.differences());
    }

    @Test
    void reportsDirectYahooAnalystConsensusDriftBeforeDerivedExpectations() {
        var evidence = sampleEvidence();
        var legacyExpectations = new CompanyMarketExpectations(10.0, 5.0, 0.5);
        var fundamentals = normalization.normalize(
                new Ticker("TEST"), "0000000001", evidence, 50.0, LocalDate.now(CLOCK)
        );
        var score = scoring.evaluate(fundamentals.scoringFinancials());
        var buyScore = buyScoring.evaluate(fundamentals.scoringFinancials(), score, legacyExpectations);
        var service = new EvaluateCompanyResearchParityService(
                new StubCompanyReadPort(
                        research(fundamentals, score, buyScore, legacyExpectations, 50.0),
                        new AtomicReference<>()
                ),
                ticker -> new CompanyIdentity("TEST", "0000000001", "Test Company"),
                cik -> evidence,
                ticker -> quote(50.0),
                ticker -> new CompanyAnalystConsensus(1.1, 11.0),
                ticker -> analystHistory(legacyExpectations),
                normalization,
                expectationsPolicy,
                scoring,
                buyScoring,
                CLOCK
        );

        var report = service.evaluate("TEST");

        assertTrue(!report.analystConsensusMatched());
        assertEquals(1.0, report.legacyAnalystConsensus().analystScore());
        assertEquals(1.1, report.springAnalystConsensus().analystScore());
        assertTrue(report.differences().contains("analystConsensus.analystScore"));
        assertTrue(report.differences().contains("analystConsensus.upsidePct"));
    }

    private static Research research(
            CompanyFundamentalsSnapshot value,
            CompanyScore score,
            CompanyBuyScore buyScore,
            CompanyMarketExpectations expectations,
            double price
    ) {
        var profile = object(Map.of("ticker", text(value.ticker().value()), "cik", text(value.cik())));
        var quote = object(Map.of(
                "symbol", text(value.ticker().value()),
                "price", number(price),
                "date", text("2026-07-17")
        ));
        var financialFields = new LinkedHashMap<String, StructuredValue>();
        financialFields.put("ticker", text(value.ticker().value()));
        financialFields.put("cik", text(value.cik()));
        financialFields.put("asOf", text(value.asOf()));
        financialFields.put("revenueTtm", number(value.revenueTtm()));
        financialFields.put("operatingIncomeTtm", number(value.operatingIncomeTtm()));
        financialFields.put("netIncomeTtm", number(value.netIncomeTtm()));
        financialFields.put("freeCashFlowTtm", number(value.freeCashFlowTtm()));
        financialFields.put("cash", number(value.cash()));
        financialFields.put("debt", number(value.debt()));
        financialFields.put("currentAssets", number(value.currentAssets()));
        financialFields.put("currentLiabilities", number(value.currentLiabilities()));
        financialFields.put("receivables", number(value.receivables()));
        financialFields.put("inventory", number(value.inventory()));
        financialFields.put("capexTtm", number(value.capexTtm()));
        financialFields.put("operatingCashFlowTtm", number(value.operatingCashFlowTtm()));
        financialFields.put("sharesOutstanding", number(value.sharesOutstanding()));
        financialFields.put("marketCap", number(value.marketCap()));
        financialFields.put("enterpriseValue", number(value.enterpriseValue()));
        financialFields.put("revenueGrowthYoY", number(value.revenueGrowthYoY()));
        financialFields.put("operatingMargin", number(value.operatingMargin()));
        financialFields.put("operatingMarginTrend", number(value.operatingMarginTrend()));
        financialFields.put("freeCashFlowMargin", number(value.freeCashFlowMargin()));
        financialFields.put("netDebtToRevenue", number(value.netDebtToRevenue()));
        financialFields.put("evToSales", number(value.evToSales()));
        financialFields.put("evToFcf", number(value.evToFcf()));
        financialFields.put("shareDilutionYoY", number(value.shareDilutionYoY()));
        financialFields.put("stockCompToRevenue", number(value.stockCompToRevenue()));
        financialFields.put("roe", number(value.roe()));
        financialFields.put("currentRatio", number(value.currentRatio()));
        financialFields.put("receivablesToRevenue", number(value.receivablesToRevenue()));
        financialFields.put("inventoryToRevenue", number(value.inventoryToRevenue()));
        financialFields.put("estimateUpsidePct", number(expectations.estimateUpsidePct()));
        financialFields.put(
                "analystScore",
                number(expectations.analystScoreRevision30d() == null ? null : 1.0)
        );
        // The cutover seed historically stored target-upside movement under
        // estimateRevision30d. The anti-corruption projection must reinterpret
        // it as targetUpsideChange30d, never as an EPS estimate revision.
        financialFields.put("estimateRevision30d", number(expectations.targetUpsideChange30d()));
        financialFields.put("analystScoreRevision30d", number(expectations.analystScoreRevision30d()));
        return new Research(
                profile,
                quote,
                object(financialFields),
                score(score),
                buyScore(buyScore),
                array(), array(), array(), NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, array()
        );
    }

    private static ObjectValue score(CompanyScore value) {
        return object(Map.of(
                "ticker", text(value.ticker().value()),
                "totalScore", number(value.totalScore()),
                "growth", breakdown(value.growth()),
                "quality", breakdown(value.quality()),
                "valuation", breakdown(value.valuation()),
                "balanceSheet", breakdown(value.balanceSheet()),
                "reasons", texts(value.reasons())
        ));
    }

    private static ObjectValue breakdown(ScoreBreakdown value) {
        return object(Map.of("value", number(value.value()), "reasons", texts(value.reasons())));
    }

    private static ObjectValue buyScore(CompanyBuyScore value) {
        var label = switch (value.label()) {
            case FAVORABLE -> "매수 우호";
            case SELECTIVE -> "선별 접근";
            case CHASE_RISK -> "추격 주의";
        };
        return object(Map.of(
                "appealScore", number(value.appealScore()),
                "crowdingScore", number(value.crowdingScore()),
                "buyScore", number(value.buyScore()),
                "label", text(label),
                "reasons", texts(value.reasons())
        ));
    }

    private static CompanyFundamentalsSnapshot copyWithMarketCap(
            CompanyFundamentalsSnapshot value,
            double marketCap
    ) {
        return new CompanyFundamentalsSnapshot(
                value.ticker(), value.cik(), value.asOf(), value.revenueTtm(), value.operatingIncomeTtm(),
                value.netIncomeTtm(), value.freeCashFlowTtm(), value.cash(), value.debt(), value.currentAssets(),
                value.currentLiabilities(), value.receivables(), value.inventory(), value.capexTtm(),
                value.operatingCashFlowTtm(), value.sharesOutstanding(), marketCap, value.enterpriseValue(),
                value.revenueGrowthYoY(), value.operatingMargin(), value.operatingMarginTrend(),
                value.freeCashFlowMargin(), value.netDebtToRevenue(), value.evToSales(), value.evToFcf(),
                value.shareDilutionYoY(), value.stockCompToRevenue(), value.roe(), value.currentRatio(),
                value.receivablesToRevenue(), value.inventoryToRevenue()
        );
    }

    private static CompanyFundamentalsEvidence sampleEvidence() {
        return new CompanyFundamentalsEvidence(
                List.of(
                        quarter("Q1", "2025-03-31", 110), quarter("Q2", "2025-06-30", 120),
                        quarter("Q3", "2025-09-30", 130), quarter("Q4", "2025-12-31", 140),
                        annual("2025-12-31", 500), annual("2024-12-31", 400)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 28), quarter("Q2", "2025-06-30", 30),
                        quarter("Q3", "2025-09-30", 32), quarter("Q4", "2025-12-31", 35)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 20), quarter("Q2", "2025-06-30", 22),
                        quarter("Q3", "2025-09-30", 24), quarter("Q4", "2025-12-31", 26)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 30), quarter("Q2", "2025-06-30", 31),
                        quarter("Q3", "2025-09-30", 33), quarter("Q4", "2025-12-31", 35)
                ),
                List.of(
                        quarter("Q1", "2025-03-31", 8), quarter("Q2", "2025-06-30", 8),
                        quarter("Q3", "2025-09-30", 9), quarter("Q4", "2025-12-31", 10)
                ),
                List.of(point("2025-12-31", 150)), List.of(point("2025-12-31", 90)),
                List.of(point("2025-12-31", 10)), List.of(), List.of(point("2025-12-31", 500)),
                List.of(point("2025-12-31", 210)), List.of(point("2025-12-31", 120)),
                List.of(point("2025-12-31", 75)), List.of(point("2025-12-31", 30))
        );
    }

    private static CompanyFundamentalsEvidence sparseSuccessorEvidence() {
        return new CompanyFundamentalsEvidence(
                List.of(
                        annual("2025-12-31", 500),
                        durationQuarter("Q2", "2025-01-01", "2025-06-30", 250),
                        durationQuarter("Q2", "2025-04-01", "2025-06-30", 120),
                        durationQuarter("Q2", "2026-01-01", "2026-06-30", 280),
                        durationQuarter("Q2", "2026-04-01", "2026-06-30", 145)
                ),
                List.of(), List.of(annual("2025-12-31", 10)), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()
        );
    }

    private static FinancialFactPoint quarter(String fiscalPeriod, String endDate, double value) {
        return new FinancialFactPoint(value, "10-Q", fiscalPeriod, endDate);
    }

    private static FinancialFactPoint durationQuarter(
            String fiscalPeriod,
            String startDate,
            String endDate,
            double value
    ) {
        return new FinancialFactPoint(value, "10-Q", fiscalPeriod, endDate, startDate);
    }

    private static FinancialFactPoint annual(String endDate, double value) {
        return new FinancialFactPoint(value, "10-K", "FY", endDate);
    }

    private static FinancialFactPoint point(String endDate, double value) {
        return new FinancialFactPoint(value, null, null, endDate);
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }

    private static CompanyMarketQuote quote(double price) {
        return new CompanyMarketQuote("TEST", price, LocalDate.parse("2026-07-17"));
    }

    private static CompanyAnalystConsensus analystConsensus(CompanyMarketExpectations expectations) {
        var currentScore = expectations.analystScoreRevision30d() == null ? null : 1.0;
        return new CompanyAnalystConsensus(
                currentScore,
                expectations.estimateUpsidePct(),
                expectations.estimateRevision7d(),
                expectations.estimateRevision30d(),
                expectations.estimateRevision90d()
        );
    }

    private static CompanyAnalystHistoryRead analystHistory(CompanyMarketExpectations expectations) {
        var currentScore = expectations.analystScoreRevision30d() == null ? null : 1.0;
        var previousScore = currentScore == null
                ? null
                : currentScore - expectations.analystScoreRevision30d();
        var previousUpside = expectations.estimateUpsidePct() == null || expectations.targetUpsideChange30d() == null
                ? null
                : expectations.estimateUpsidePct() - expectations.targetUpsideChange30d();
        var history = List.of(new CompanyAnalystHistoryPoint(
                        LocalDate.parse("2026-06-19"),
                        previousScore,
                        previousUpside
                ));
        return new CompanyAnalystHistoryRead(
                "TEST",
                history,
                CompanyAnalystHistoryRead.Mode.SEED_ONLY,
                CompanyAnalystHistoryRead.Source.SEED,
                CompanyAnalystHistoryRead.SourceState.AVAILABLE,
                CompanyAnalystHistoryRead.SourceState.NOT_READ,
                false,
                List.of(),
                history.size(),
                null,
                history.getFirst().date(),
                null
        );
    }

    private static StructuredValue number(Number value) {
        if (value == null) return NullValue.INSTANCE;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new NumberValue(value.longValue());
        }
        return new NumberValue(BigDecimal.valueOf(value.doubleValue()));
    }

    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream().map(EvaluateCompanyResearchParityServiceTest::text).map(StructuredValue.class::cast).toList());
    }

    private static ArrayValue array() {
        return new ArrayValue(List.of());
    }

    private static ObjectValue object(Map<String, StructuredValue> fields) {
        return new ObjectValue(fields);
    }

    private static final class StubCompanyReadPort implements LoadCompanyReadPort {
        private final Research research;
        private final AtomicReference<String> observedTicker;

        private StubCompanyReadPort(Research research, AtomicReference<String> observedTicker) {
            this.research = research;
            this.observedTicker = observedTicker;
        }

        @Override
        public SearchResult search(String normalizedQuery, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SummaryResult summaries(List<String> normalizedTickers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Research detail(String normalizedTicker) {
            observedTicker.set(normalizedTicker);
            return research;
        }
    }
}
