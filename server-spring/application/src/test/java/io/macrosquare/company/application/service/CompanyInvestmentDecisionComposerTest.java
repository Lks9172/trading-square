package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyInvestmentDecisionComposerTest {

    private final CompanyInvestmentDecisionComposer composer =
            new CompanyInvestmentDecisionComposer(new CompanyInvestmentDecisionPolicy());

    @Test
    void composesSixAxisDecisionAndKeepsLegacyProjectionInformation() {
        var source = research();

        var result = composer.compose(source, LocalDate.parse("2026-07-26"));

        var verdicts = object(result.verdicts());
        var decision = object(verdicts.fields().get("investmentDecision"));
        assertEquals(CompanyInvestmentDecisionPolicy.VERSION, text(decision, "version"));
        assertTrue(List.of("STRONG BUY", "BUY").contains(text(decision, "action")));
        assertTrue(number(decision, "investmentMeritScore") >= 70);
        assertTrue(number(decision, "entryReadinessScore") >= 70);

        var dimensions = object(decision.fields().get("dimensions"));
        assertEquals(
                List.of("quality", "valuation", "catalyst", "sector", "timing"),
                dimensions.fields().keySet().stream().toList()
        );
        assertFalse(array(decision, "whyNow").values().isEmpty());
        assertFalse(array(decision, "thesisBreaks").values().isEmpty());
        assertFalse(array(decision, "thesisBreaks").values().stream()
                .filter(TextValue.class::isInstance)
                .map(TextValue.class::cast)
                .map(TextValue::value)
                .anyMatch("시장점유율 훼손"::equals));
        var eligibility = object(decision.fields().get("scaleInEligibility"));
        assertEquals("ELIGIBLE", text(eligibility, "state"));
        assertTrue(number(eligibility, "portfolioConcentrationCapPct") > 0);

        var outlooks = array(decision, "forwardOutlooks");
        assertEquals(3, outlooks.values().size());
        assertEquals("WALK_FORWARD", text(object(outlooks.values().getFirst()), "method"));
        assertEquals(12, number(object(outlooks.values().getFirst()), "sampleCount"));

        var sizing = object(result.positionSizing());
        assertEquals(
                number(object(source.positionSizing()), "targetPositionPct"),
                number(sizing, "targetPositionPct")
        );
        assertEquals(text(decision, "action"), text(sizing, "action"));
        assertTrue(number(sizing, "initialEntryPctOfTarget") > 0);
        assertEquals(
                10,
                number(object(sizing.fields().get("legacyPolicySnapshot")), "initialEntryPctOfTarget")
        );
        assertTrue(array(sizing, "reasons").values().size() >= 4);

        var bridge = object(result.executionBridge());
        assertEquals(text(decision, "action"), text(bridge, "companyAction"));
        assertNotNull(bridge.fields().get("alignment"));
        assertEquals(source.financials(), result.financials());
        assertEquals("legacy-field-preserved", text(verdicts, "legacyMarker"));
        assertEquals(
                CompanyInvestmentDecisionPolicy.VERSION,
                text(object(result.correctionAssessment()), "authoritativeSource")
        );
        assertEquals(
                CompanyInvestmentDecisionPolicy.VERSION,
                text(object(result.thesisMonitor()), "authoritativeSource")
        );
    }

    @Test
    void exposesDataWarningsAndNeverPresentsHeuristicAsWalkForwardProbability() {
        var source = researchWithoutHistory();

        var result = composer.compose(source, LocalDate.parse("2026-07-26"));

        var decision = object(object(result.verdicts()).fields().get("investmentDecision"));
        var quality = object(decision.fields().get("dataQuality"));
        assertFalse(array(quality, "warnings").values().isEmpty());
        var outlook = object(array(decision, "forwardOutlooks").values().getFirst());
        assertEquals("SCORE_HEURISTIC", text(outlook, "method"));
        assertTrue(text(decision, "probabilityNotice").contains("확률이 아닌"));
        assertEquals(
                1,
                array(decision, "whyWait").values().stream()
                        .filter(TextValue.class::isInstance)
                        .map(TextValue.class::cast)
                        .map(TextValue::value)
                        .filter("최근 구조화된 가이던스 방향을 확인하지 못했습니다."::equals)
                        .count()
        );
    }

    @Test
    void futureDatedSourceEvidenceCannotAuthorizeABuy() {
        var source = research();
        var futureDated = new Research(
                source.profile(),
                withField(object(source.quote()), "date", textValue("2099-01-01")),
                withField(object(source.financials()), "asOf", textValue("2099-01-01")),
                source.score(), source.buyScore(), source.filings(), source.irMaterials(),
                source.highlights(), source.peerGroup(), source.bottleneck(), source.narrative(),
                source.capitalFlow(), source.cashFlowQuality(), source.multipleInsight(),
                source.guidanceInsight(), source.timeframeView(), source.correctionAssessment(),
                source.thesisMonitor(), source.reversalConfirmation(), source.sectorContext(),
                source.verdicts(), source.bottomSignal(), source.positionSizing(),
                source.executionBridge(), source.peers()
        );

        var result = composer.compose(futureDated, LocalDate.parse("2026-07-26"));
        var decision = object(object(result.verdicts()).fields().get("investmentDecision"));
        var quality = object(decision.fields().get("dataQuality"));

        assertEquals("HOLD", text(decision, "action"));
        assertEquals(0, number(object(decision.fields().get("entryStrategy")), "initialEntryPctOfTarget"));
        assertTrue(array(quality, "warnings").values().stream()
                .filter(TextValue.class::isInstance)
                .map(TextValue.class::cast)
                .map(TextValue::value)
                .anyMatch(value -> value.contains("가격 기준일")));
    }

    private static Research research() {
        return new Research(
                object("ticker", textValue("TEST")),
                object("symbol", textValue("TEST"), "date", textValue("2026-07-25")),
                object(
                        "asOf", textValue("2026-06-30"),
                        "fundamentalsStatus", textValue("CURRENT"),
                        "revenueTtm", numberValue(100_000),
                        "operatingIncomeTtm", numberValue(32_000),
                        "freeCashFlowTtm", numberValue(25_000),
                        "revenueGrowthYoY", decimalValue("18.0"),
                        "operatingMargin", decimalValue("32.0"),
                        "operatingMarginTrend", decimalValue("2.0"),
                        "freeCashFlowMargin", decimalValue("25.0"),
                        "roic", decimalValue("22.0"),
                        "roe", decimalValue("30.0"),
                        "netDebtToRevenue", decimalValue("-0.1"),
                        "shareDilutionYoY", decimalValue("0.5"),
                        "shareDilution3yCagr", decimalValue("1.0"),
                        "stockCompToRevenue", decimalValue("7.0"),
                        "accrualRatio", decimalValue("1.0"),
                        "evToSales", decimalValue("7.0"),
                        "evToFcf", decimalValue("25.0"),
                        "estimateUpsidePct", decimalValue("16.0"),
                        "estimateRevision7d", decimalValue("3.0"),
                        "estimateRevision30d", decimalValue("6.0"),
                        "estimateRevision90d", decimalValue("4.0"),
                        "analystScoreRevision7d", decimalValue("-0.1"),
                        "analystScoreRevision30d", decimalValue("-0.2"),
                        "analystScoreRevision90d", decimalValue("-0.1")
                ),
                object(
                        "totalScore", numberValue(84),
                        "growth", object("value", numberValue(82)),
                        "quality", object("value", numberValue(88)),
                        "valuation", object("value", numberValue(74)),
                        "balanceSheet", object("value", numberValue(86))
                ),
                object(
                        "appealScore", numberValue(84),
                        "crowdingScore", numberValue(35),
                        "buyScore", numberValue(82)
                ),
                array(),
                array(),
                array(),
                NullValue.INSTANCE,
                object(
                        "score", numberValue(82),
                        "switchingCost", numberValue(80),
                        "pricingPower", textValue("높음"),
                        "leadTimeSignal", textValue("강함"),
                        "backlogSignal", textValue("강함")
                ),
                object(
                        "stage", textValue("EARLY"),
                        "trend", textValue("HEATING"),
                        "heatScore", numberValue(45)
                ),
                NullValue.INSTANCE,
                object(
                        "cashConversionScore", numberValue(86),
                        "earningsQualityScore", numberValue(84)
                ),
                object(
                        "premiumPctVsPeer", decimalValue("-8.0"),
                        "premiumPctVsPeerMedian", decimalValue("-12.0"),
                        "valuationVsInternalRange", textValue("저평가권"),
                        "valuationVsPeer", textValue("할인"),
                        "multipleCompressionRisk", textValue("낮음"),
                        "rateSensitivity", textValue("보통"),
                        "narrativePremium", textValue("보통")
                ),
                object("stance", textValue("raised"), "actionBias", textValue("공격 가능")),
                timeframe(true),
                object("correctionScore", numberValue(78), "trendBreakRiskScore", numberValue(22)),
                object(
                        "status", textValue("유지"),
                        "breakConditions", array(textValue("가이던스 하향"), textValue("시장점유율 훼손"))
                ),
                object("status", textValue("STRONG"), "score", numberValue(86)),
                object(
                        "sectorId", textValue("technology"),
                        "buyScore", numberValue(78),
                        "qualityScore", numberValue(82),
                        "appealScore", numberValue(80),
                        "crowdingScore", numberValue(38),
                        "valuationScore", numberValue(72),
                        "earningsRevisionScore", numberValue(84),
                        "rotationScore", numberValue(82),
                        "macroFitScore", numberValue(76),
                        "relativeStrengthScore", numberValue(84),
                        "fundamentalScore", numberValue(80),
                        "flowScore", numberValue(79),
                        "stance", textValue("favored"),
                        "rotationState", textValue("NEXT_CANDIDATE"),
                        "expectedLeadershipWindow", textValue("1_3m")
                ),
                object("legacyMarker", textValue("legacy-field-preserved")),
                object(
                        "score", numberValue(84),
                        "earningsBottomScore", numberValue(80),
                        "priceBottomScore", numberValue(82),
                        "volumeConfirmationScore", numberValue(88),
                        "failureRiskScore", numberValue(18),
                        "confirmedBottom", object("score", numberValue(86), "state", textValue("확신")),
                        "technicalConfirmation", object("score", numberValue(84), "state", textValue("매집 우위")),
                        "priceStructure", object(
                                "score", numberValue(84),
                                "trendState", textValue("UPTREND"),
                                "bearishReversalStage", textValue("INTACT"),
                                "recoveryStage", textValue("RETEST_HELD"),
                                "priceLocation", textValue("SUPPORT_ZONE"),
                                "movingAverageState", textValue("BULLISH_ALIGNED"),
                                "rsi14", decimalValue("43.0"),
                                "oversoldConfluence", booleanValue(false),
                                "stopHuntReclaim", booleanValue(false),
                                "volumeBreakout", booleanValue(false)
                        )
                ),
                object(
                        "targetPositionPct", numberValue(10),
                        "initialEntryPctOfTarget", numberValue(10),
                        "reservePctOfTarget", numberValue(90),
                        "addOnPlan", array(textValue("legacy add-on")),
                        "reasons", array(textValue("legacy reason"))
                ),
                object("action", textValue("BUY"), "actionLabel", textValue("매수")),
                array()
        );
    }

    private static Research researchWithoutHistory() {
        var source = research();
        return new Research(
                source.profile(),
                object("symbol", textValue("TEST"), "date", textValue("2025-01-01")),
                object(
                        "asOf", textValue("2024-01-01"),
                        "revenueTtm", numberValue(100),
                        "operatingIncomeTtm", numberValue(150)
                ),
                source.score(), source.buyScore(), source.filings(), source.irMaterials(),
                source.highlights(), source.peerGroup(), source.bottleneck(), source.narrative(),
                source.capitalFlow(), source.cashFlowQuality(), NullValue.INSTANCE,
                NullValue.INSTANCE, timeframe(false), source.correctionAssessment(),
                source.thesisMonitor(), source.reversalConfirmation(), NullValue.INSTANCE,
                source.verdicts(), source.bottomSignal(), source.positionSizing(),
                source.executionBridge(), source.peers()
        );
    }

    private static ObjectValue timeframe(boolean withHistory) {
        return object(
                "shortTerm", object("score", numberValue(82)),
                "swingTerm", object("score", numberValue(84)),
                "longTerm", object("score", numberValue(86)),
                "validation", withHistory
                        ? object("horizons", array(
                                horizon("SHORT_TERM", 21, 12, "75.0", "8.0", "66.7", "6.2", "-4.1"),
                                horizon("SWING_TERM", 63, 10, "80.0", "15.0", "60.0", "11.5", "-7.2"),
                                horizon("LONG_TERM", 126, 8, "87.5", "20.0", "62.5", "20.2", "-9.0")
                        ))
                        : object("horizons", array())
        );
    }

    private static ObjectValue horizon(
            String horizon,
            long days,
            long count,
            String positive,
            String target,
            String targetHit,
            String average,
            String drawdown
    ) {
        return object(
                "horizon", textValue(horizon),
                "forwardTradingDays", numberValue(days),
                "signalCount", numberValue(count),
                "positiveHitRatePct", decimalValue(positive),
                "targetReturnPct", decimalValue(target),
                "targetHitRatePct", decimalValue(targetHit),
                "averageReturnPct", decimalValue(average),
                "averageMaxDrawdownPct", decimalValue(drawdown)
        );
    }

    private static ObjectValue object(StructuredValue value) {
        return value instanceof ObjectValue object ? object : new ObjectValue(new LinkedHashMap<>());
    }

    private static ObjectValue withField(ObjectValue source, String key, StructuredValue value) {
        var fields = new LinkedHashMap<>(source.fields());
        fields.put(key, value);
        return new ObjectValue(fields);
    }

    private static ObjectValue object(Object... entries) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        for (var index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], (StructuredValue) entries[index + 1]);
        }
        return new ObjectValue(fields);
    }

    private static ArrayValue array(StructuredValue... values) {
        return new ArrayValue(List.of(values));
    }

    private static ArrayValue array(ObjectValue parent, String key) {
        return parent.fields().get(key) instanceof ArrayValue array ? array : new ArrayValue(List.of());
    }

    private static String text(ObjectValue parent, String key) {
        return parent.fields().get(key) instanceof TextValue value ? value.value() : null;
    }

    private static long number(ObjectValue parent, String key) {
        return parent.fields().get(key) instanceof NumberValue value
                ? value.value().longValue()
                : Long.MIN_VALUE;
    }

    private static TextValue textValue(String value) {
        return new TextValue(value);
    }

    private static NumberValue numberValue(long value) {
        return new NumberValue(value);
    }

    private static NumberValue decimalValue(String value) {
        return new NumberValue(new BigDecimal(value));
    }

    private static BooleanValue booleanValue(boolean value) {
        return new BooleanValue(value);
    }
}
