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
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.bottom.BottomActionBias;
import io.macrosquare.company.domain.bottom.BottomPatternPhase;
import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPatternPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceContext;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.BottomPriceSignalPolicy;
import io.macrosquare.company.domain.bottom.BottomStructureState;
import io.macrosquare.company.domain.bottom.DeepBottomPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.DeepBottomState;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.ReversalConfirmationEvidence;
import io.macrosquare.company.domain.bottom.ReversalConfirmationPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCompanyPriceSignalParityServiceTest {

    private final BottomPriceContextPolicy contextPolicy = new BottomPriceContextPolicy(new BottomPatternPolicy());
    private final BottomPriceSignalPolicy priceSignalPolicy = new BottomPriceSignalPolicy();
    private final DeepBottomPolicy deepBottomPolicy = new DeepBottomPolicy();
    private final ReversalConfirmationPolicy reversalPolicy = new ReversalConfirmationPolicy();

    @Test
    void comparesEveryLegacyPriceSignalStageWithoutChangingTheServingResult() {
        var history = fixture();
        var context = contextPolicy.evaluate(history);
        var priceSignal = priceSignalPolicy.evaluate(context);
        var confirmedBottom = deepBottomPolicy.evaluate(context.toDeepBottomEvidence(priceSignal.failureRiskScore()));
        var reversal = reversal(context, priceSignal, confirmedBottom);
        var observedLegacyTicker = new AtomicReference<String>();
        var observedHistoryTicker = new AtomicReference<String>();
        var baseline = research(
                "TEST", context, priceSignal, confirmedBottom, reversal, priceSignal.priceResetScore());
        var service = service(
                baseline,
                history,
                observedLegacyTicker,
                observedHistoryTicker
        );

        var report = service.evaluate(" test ");

        assertTrue(report.allMatched());
        assertTrue(report.priceHistoryMatched());
        assertTrue(report.markersMatched());
        assertTrue(report.priceSignalMatched());
        assertTrue(report.confirmedBottomMatched());
        assertTrue(report.reversalConfirmationMatched());
        assertTrue(report.legacyAvailable());
        assertTrue(report.differences().isEmpty());
        assertEquals("TEST", observedLegacyTicker.get());
        assertEquals("TEST", observedHistoryTicker.get());
        assertEquals(60, report.spring().history().pointCount());
        assertEquals(60, report.spring().priceStructure().points().size());
        assertTrue(report.spring().priceStructure().methodology().contains("지지·저항"));
        assertEquals(60, report.spring().macdMomentum().daily().sourcePointCount());
        assertTrue(report.spring().macdMomentum().daily().macd() != null);
        assertEquals(BottomStructureState.BOTTOM_ATTEMPT, report.spring().priceSignal().structureState());
        assertEquals(DeepBottomState.CANDIDATE, report.spring().confirmedBottom().state());

        var pending = CompanyResearchProjectionComposer.pendingCurrentPriceSignals(
                baseline);
        var pendingBottom = assertInstanceOf(ObjectValue.class, pending.bottomSignal());
        assertTrue(assertInstanceOf(ArrayValue.class, pendingBottom.fields().get("reasons")).values().isEmpty());
        assertFalse(textValues(pendingBottom.fields().get("cautions")).contains("legacy caution"));
        assertFalse(textValues(pendingBottom.fields().get("failureSignals")).contains("legacy failure"));
        var composed = CompanyResearchProjectionComposer.priceSignals(pending, report);
        var bottom = assertInstanceOf(ObjectValue.class, composed.bottomSignal());
        assertInstanceOf(NumberValue.class, bottom.fields().get("score"));
        assertTrue(((TextValue) bottom.fields().get("summary")).value().contains("확신형 바닥 판정은 후보"));
        assertFalse(((TextValue) bottom.fields().get("summary")).value().contains("계산 대기"));
        var currentMetricKeys = assertInstanceOf(ArrayValue.class, bottom.fields().get("metrics")).values().stream()
                .filter(ObjectValue.class::isInstance)
                .map(ObjectValue.class::cast)
                .map(metric -> ((TextValue) metric.fields().get("key")).value())
                .toList();
        assertTrue(currentMetricKeys.containsAll(List.of("price", "pattern", "volume", "absorption")));
        assertInstanceOf(ObjectValue.class, bottom.fields().get("macdMomentum"));
    }

    @Test
    void reportsOneFieldDriftAndCanonicalizesDotTickersForYahoo() {
        var history = fixture();
        var context = contextPolicy.evaluate(history);
        var priceSignal = priceSignalPolicy.evaluate(context);
        var confirmedBottom = deepBottomPolicy.evaluate(context.toDeepBottomEvidence(priceSignal.failureRiskScore()));
        var reversal = reversal(context, priceSignal, confirmedBottom);
        var observedLegacyTicker = new AtomicReference<String>();
        var observedHistoryTicker = new AtomicReference<String>();
        var service = service(
                research("BRK-B", context, priceSignal, confirmedBottom, reversal, priceSignal.priceResetScore() + 1),
                history,
                observedLegacyTicker,
                observedHistoryTicker
        );

        var report = service.evaluate("brk.b");

        assertFalse(report.allMatched());
        assertFalse(report.priceSignalMatched());
        assertEquals(List.of("priceSignal.priceResetScore"), report.differences());
        assertEquals("BRK.B", observedLegacyTicker.get());
        assertEquals("BRK-B", observedHistoryTicker.get());
        assertEquals("BRK-B", report.ticker());
    }

    @Test
    void currentSignalsIgnoreOlderWalkForwardHistoryOutsideTheConfiguredWindow() {
        var currentHistory = fixture();
        var context = contextPolicy.evaluate(currentHistory);
        var priceSignal = priceSignalPolicy.evaluate(context);
        var confirmedBottom = deepBottomPolicy.evaluate(context.toDeepBottomEvidence(priceSignal.failureRiskScore()));
        var reversal = reversal(context, priceSignal, confirmedBottom);
        var fullHistory = new ArrayList<BottomPatternPoint>();
        fullHistory.add(new BottomPatternPoint(LocalDate.parse("2024-01-02"), 10.0, 1_000.0));
        fullHistory.addAll(currentHistory);
        var service = service(
                research("TEST", context, priceSignal, confirmedBottom, reversal, priceSignal.priceResetScore()),
                fullHistory,
                new AtomicReference<>(),
                new AtomicReference<>()
        );

        var report = service.evaluate("TEST");

        assertTrue(report.allMatched());
        assertEquals(context.reboundFromLowPct(), report.springContext().reboundFromLowPct());
        assertEquals(currentHistory.size(), report.spring().history().pointCount());
    }

    @Test
    void unadjustedSplitHistoryIsRejectedBeforeItCanCreateAFakeBottomSignal() {
        var history = new ArrayList<>(fixture());
        var splitDate = history.get(30).date();
        for (var index = 0; index < 30; index++) {
            var value = history.get(index);
            history.set(index, new BottomPatternPoint(
                    value.date(), value.close() * 10, value.volume(),
                    value.high() == null ? null : value.high() * 10,
                    value.low() == null ? null : value.low() * 10
            ));
        }
        var previous = history.get(29);
        var firstPostSplit = history.get(30);
        history.set(29, new BottomPatternPoint(
                previous.date(), firstPostSplit.close() * 10, previous.volume(),
                firstPostSplit.close() * 10.1, firstPostSplit.close() * 9.9
        ));
        var context = contextPolicy.evaluate(fixture());
        var priceSignal = priceSignalPolicy.evaluate(context);
        var confirmedBottom = deepBottomPolicy.evaluate(context.toDeepBottomEvidence(priceSignal.failureRiskScore()));
        var reversal = reversal(context, priceSignal, confirmedBottom);
        var service = service(
                research("SPLIT", context, priceSignal, confirmedBottom, reversal, priceSignal.priceResetScore()),
                history,
                new AtomicReference<>(),
                new AtomicReference<>()
        );

        var error = assertThrows(
                io.macrosquare.company.application.port.out.CompanyResearchParityUnavailableException.class,
                () -> service.evaluate("SPLIT")
        );

        assertTrue(error.getMessage().contains("corporate-action basis"));
        assertTrue(error.getCause().getMessage().contains(splitDate.toString()));
    }

    @Test
    void malformedLegacyProjectionCannotSuppressCurrentPriceSignals() {
        var history = fixture();
        var service = service(
                CompanyRevenueMixComposerTest.research(true),
                history,
                new AtomicReference<>(),
                new AtomicReference<>()
        );

        var report = service.evaluate("NVDA");

        assertFalse(report.legacyAvailable());
        assertFalse(report.allMatched());
        assertTrue(report.differences().getFirst().startsWith("legacyProjection.unavailable"));
        assertEquals(history.size(), report.spring().history().pointCount());
        assertTrue(report.spring().priceStructure().score() >= 0);
        assertTrue(report.spring().macdMomentum().daily().macd() != null);
    }

    @Test
    void currentOnlyEvaluationSkipsLegacyComparisonAndWalkForwardValidation() {
        var history = fixture();
        var observedLegacyTicker = new AtomicReference<String>();
        var observedHistoryTicker = new AtomicReference<String>();
        var service = service(
                CompanyRevenueMixComposerTest.research(true),
                history,
                observedLegacyTicker,
                observedHistoryTicker
        );

        var report = service.evaluateCurrent(" nvda ");

        assertEquals("NVDA", observedHistoryTicker.get());
        assertNull(observedLegacyTicker.get());
        assertNull(report.spring().walkForwardValidation());
        assertFalse(report.legacyAvailable());
        assertEquals(List.of("legacyProjection.skippedForCurrentOnly"), report.differences());
        assertEquals(history.size(), report.spring().history().pointCount());
        assertTrue(report.spring().priceStructure().score() >= 0);
    }

    private EvaluateCompanyPriceSignalParityService service(
            Research research,
            List<BottomPatternPoint> history,
            AtomicReference<String> observedLegacyTicker,
            AtomicReference<String> observedHistoryTicker
    ) {
        return new EvaluateCompanyPriceSignalParityService(
                new StubCompanyReadPort(research, observedLegacyTicker),
                ticker -> {
                    observedHistoryTicker.set(ticker);
                    return history;
                },
                contextPolicy,
                priceSignalPolicy,
                deepBottomPolicy,
                reversalPolicy,
                new io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy(),
                new io.macrosquare.company.domain.bottom.PriceStructurePolicy(),
                new io.macrosquare.company.domain.horizon.CompanyHorizonWalkForwardPolicy(
                        contextPolicy,
                        priceSignalPolicy,
                        deepBottomPolicy,
                        reversalPolicy,
                        new io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy()
                ),
                1_900
        );
    }

    private ReversalConfirmation reversal(
            BottomPriceContext context,
            BottomPriceSignal priceSignal,
            DeepBottomSignal confirmedBottom
    ) {
        var history = context.chartPoints();
        var technical = new io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy().evaluate(history);
        var structure = new io.macrosquare.company.domain.bottom.PriceStructurePolicy().evaluate(history);
        return reversalPolicy.evaluate(new ReversalConfirmationEvidence(
                confirmedBottom,
                technical.score(),
                structure.score(),
                priceSignal.structureState(),
                context.pattern().confirmPoint().date(),
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private static Research research(
            String ticker,
            BottomPriceContext context,
            BottomPriceSignal signal,
            DeepBottomSignal confirmed,
            ReversalConfirmation reversal,
            int legacyPriceResetScore
    ) {
        var bottom = object(
                "score", number(50),
                "state", text(structureState(signal.structureState())),
                "priceBottomScore", number(signal.priceBottomScore()),
                "volumeConfirmationScore", number(signal.volumeConfirmationScore()),
                "failureRiskScore", number(signal.failureRiskScore()),
                "metrics", array(
                        object("key", text("price"), "score", number(legacyPriceResetScore)),
                        object("key", text("pattern"), "score", number(signal.patternScore())),
                        object("key", text("absorption"), "score", number(signal.absorptionScore()))
                ),
                "chart", object(
                        "points", new ArrayValue(context.chartPoints().stream().map(point -> object(
                                "date", text(point.date().toString()),
                                "value", number(point.close())
                        )).map(StructuredValue.class::cast).toList()),
                        "markers", new ArrayValue(markers(context).stream().map(StructuredValue.class::cast).toList())
                ),
                "confirmedBottom", deepBottom(confirmed),
                "reasons", textArray(List.of("legacy reason")),
                "cautions", textArray(List.of("legacy caution")),
                "failureSignals", textArray(List.of("legacy failure"))
        );
        return new Research(
                object("ticker", text(ticker)),
                object(),
                object("estimateRevision30d", number(7.18)),
                object(),
                object("crowdingScore", number(42)),
                array(), array(), array(), NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, reversal(reversal), NullValue.INSTANCE,
                NullValue.INSTANCE, bottom, NullValue.INSTANCE, NullValue.INSTANCE, array()
        );
    }

    private static ObjectValue deepBottom(DeepBottomSignal value) {
        return object(
                "score", number(value.score()),
                "state", text(switch (value.state()) {
                    case UNMET -> "미충족";
                    case CANDIDATE -> "후보";
                    case CONVICTION -> "확신";
                }),
                "actionBias", text(switch (value.actionBias()) {
                    case WAIT -> "대기";
                    case OBSERVE_BUY -> "관찰 매수";
                    case SCALE_IN_BUY -> "분할 매수";
                }),
                "signalDate", nullableText(value.signalDate() == null ? null : value.signalDate().toString()),
                "daysSinceSignal", nullableNumber(value.daysSinceSignal()),
                "summary", text(value.summary()),
                "recentVolumeRatio", nullableNumber(value.recentVolumeRatio()),
                "contractionRatio", nullableNumber(value.contractionRatio()),
                "drawdown120dPct", nullableNumber(value.drawdown120dPct()),
                "ma20GapPct", nullableNumber(value.ma20GapPct()),
                "recentDrop3dPct", nullableNumber(value.recentDrop3dPct()),
                "reasons", textArray(value.reasons()),
                "cautions", textArray(value.cautions())
        );
    }

    private static ObjectValue reversal(ReversalConfirmation value) {
        return object(
                "status", text(value.status().name()),
                "score", number(value.score()),
                "signalDate", nullableText(value.signalDate() == null ? null : value.signalDate().toString()),
                "summary", text(value.summary()),
                "reasons", textArray(value.reasons()),
                "cautions", textArray(value.cautions())
        );
    }

    private static List<ObjectValue> markers(BottomPriceContext context) {
        var values = new ArrayList<ObjectValue>();
        addMarker(values, "peak", context.pattern().peakPoint());
        addMarker(values, "candidate", context.pattern().candidatePoint());
        addMarker(values, "retest", context.pattern().retestPoint());
        if (context.pattern().confirmPoint() != null) {
            addMarker(values, "confirm", context.pattern().confirmPoint());
        } else if (context.pattern().candidatePoint() != null && context.pattern().currentPoint() != null
                && !context.pattern().candidatePoint().date().equals(context.pattern().currentPoint().date())) {
            addMarker(values, context.pattern().phase() == BottomPatternPhase.RETEST ? "retest" : "current",
                    context.pattern().currentPoint());
        }
        addMarker(values, "current", context.pattern().currentPoint());
        return values;
    }

    private static void addMarker(List<ObjectValue> values, String kind, BottomPatternPoint point) {
        if (point != null) values.add(object(
                "kind", text(kind),
                "date", text(point.date().toString()),
                "value", number(point.close())
        ));
    }

    private static String structureState(BottomStructureState state) {
        return switch (state) {
            case NOT_BOTTOM -> "바닥 아님";
            case BOTTOM_ATTEMPT -> "바닥 시도";
            case RETEST -> "재시험 구간";
            case FIRST_CONFIRMATION -> "1차 확인";
            case STRUCTURAL_BOTTOM_POSSIBLE -> "구조적 바닥 가능";
        };
    }

    private static List<BottomPatternPoint> fixture() {
        double[] closes = {
                100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
                110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
                150, 121, 122, 123, 124, 115, 115, 115, 115, 115,
                100, 102, 104, 106, 107, 110, 113, 116, 118, 119,
                120, 114, 110, 107, 105, 104, 106, 110, 114, 116,
                118, 117, 116, 115, 116, 117, 118, 117, 118, 118
        };
        var date = LocalDate.parse("2025-11-01");
        var points = new ArrayList<BottomPatternPoint>();
        for (var index = 0; index < closes.length; index++) {
            points.add(new BottomPatternPoint(date.plusDays(index), closes[index], 1000.0 + index));
        }
        return points;
    }

    private static ObjectValue object(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("entries must be key/value pairs");
        var fields = new LinkedHashMap<String, StructuredValue>();
        for (var index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], (StructuredValue) entries[index + 1]);
        }
        return new ObjectValue(fields);
    }

    private static ArrayValue array(StructuredValue... values) {
        return new ArrayValue(List.of(values));
    }

    private static ArrayValue textArray(List<String> values) {
        return new ArrayValue(values.stream().map(TextValue::new).map(StructuredValue.class::cast).toList());
    }

    private static List<String> textValues(StructuredValue value) {
        if (!(value instanceof ArrayValue values)) return List.of();
        return values.values().stream().filter(TextValue.class::isInstance)
                .map(TextValue.class::cast).map(TextValue::value).toList();
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }

    private static StructuredValue nullableText(String value) {
        return value == null ? NullValue.INSTANCE : text(value);
    }

    private static NumberValue number(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new NumberValue(value.longValue());
        }
        return new NumberValue(BigDecimal.valueOf(value.doubleValue()));
    }

    private static StructuredValue nullableNumber(Number value) {
        return value == null ? NullValue.INSTANCE : number(value);
    }

    private record StubCompanyReadPort(Research research, AtomicReference<String> observedTicker)
            implements LoadCompanyReadPort {
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
