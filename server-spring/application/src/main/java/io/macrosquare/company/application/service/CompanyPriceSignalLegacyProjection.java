package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot;
import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot.ChartMarker;
import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot.PriceHistorySummary;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.domain.bottom.BottomActionBias;
import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.BottomStructureState;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.DeepBottomState;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.ReversalConfirmationStatus;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anti-corruption projection of baseline fields required by price-signal parity diagnostics. */
record CompanyPriceSignalLegacyProjection(
        String ticker,
        List<BottomPatternPoint> chartPoints,
        List<ChartMarker> markers,
        BottomPriceSignal priceSignal,
        DeepBottomSignal confirmedBottom,
        ReversalConfirmation reversalConfirmation,
        List<String> bottomReasons,
        List<String> bottomCautions,
        List<String> failureSignals
) {
    CompanyPriceSignalLegacyProjection {
        chartPoints = List.copyOf(chartPoints);
        markers = List.copyOf(markers);
        bottomReasons = List.copyOf(bottomReasons);
        bottomCautions = List.copyOf(bottomCautions);
        failureSignals = List.copyOf(failureSignals);
    }

    static CompanyPriceSignalLegacyProjection from(Research research) {
        var ticker = requiredText(research.profile(), "ticker");
        var bottom = requiredObject(research.bottomSignal(), "bottomSignal");
        var chart = requiredObject(required(bottom, "chart"), "bottomSignal.chart");
        var chartPoints = chartPoints(requiredArray(chart, "points"));
        var markers = markers(requiredArray(chart, "markers"));
        var metricScores = metricScores(requiredArray(bottom, "metrics"));
        var priceSignal = new BottomPriceSignal(
                requiredMetric(metricScores, "price"),
                requiredMetric(metricScores, "pattern"),
                requiredMetric(metricScores, "absorption"),
                requiredInteger(bottom, "volumeConfirmationScore"),
                requiredInteger(bottom, "priceBottomScore"),
                requiredInteger(bottom, "failureRiskScore"),
                structureState(requiredText(bottom, "state"))
        );
        var confirmedBottom = confirmedBottom(requiredObject(
                required(bottom, "confirmedBottom"), "bottomSignal.confirmedBottom"
        ));
        var reversal = reversal(requiredObject(
                research.reversalConfirmation(), "reversalConfirmation"
        ));
        return new CompanyPriceSignalLegacyProjection(
                ticker,
                chartPoints,
                markers,
                priceSignal,
                confirmedBottom,
                reversal,
                textList(bottom, "reasons"),
                textList(bottom, "cautions"),
                textList(bottom, "failureSignals")
        );
    }

    CompanyPriceSignalSnapshot snapshot() {
        return new CompanyPriceSignalSnapshot(
                summary(chartPoints),
                markers,
                priceSignal,
                confirmedBottom,
                reversalConfirmation
        );
    }

    private static List<BottomPatternPoint> chartPoints(ArrayValue array) {
        var values = new ArrayList<BottomPatternPoint>(array.values().size());
        for (var item : array.values()) {
            var point = requiredObject(item, "bottomSignal.chart.points[]");
            values.add(new BottomPatternPoint(
                    requiredDate(point, "date"),
                    requiredNumber(point, "value"),
                    null
            ));
        }
        return values;
    }

    private static List<ChartMarker> markers(ArrayValue array) {
        var values = new ArrayList<ChartMarker>(array.values().size());
        for (var item : array.values()) {
            var marker = requiredObject(item, "bottomSignal.chart.markers[]");
            values.add(new ChartMarker(
                    requiredText(marker, "kind"),
                    requiredDate(marker, "date"),
                    requiredNumber(marker, "value")
            ));
        }
        return values;
    }

    private static Map<String, Integer> metricScores(ArrayValue array) {
        var values = new LinkedHashMap<String, Integer>();
        for (var item : array.values()) {
            var metric = requiredObject(item, "bottomSignal.metrics[]");
            values.put(requiredText(metric, "key"), requiredInteger(metric, "score"));
        }
        return values;
    }

    private static int requiredMetric(Map<String, Integer> metrics, String key) {
        var value = metrics.get(key);
        if (value == null) throw new IllegalArgumentException("bottom metric " + key + " is required");
        return value;
    }

    private static DeepBottomSignal confirmedBottom(ObjectValue value) {
        return new DeepBottomSignal(
                requiredInteger(value, "score"),
                deepBottomState(requiredText(value, "state")),
                actionBias(requiredText(value, "actionBias")),
                nullableDate(value, "signalDate"),
                nullableInteger(value, "daysSinceSignal"),
                requiredText(value, "summary"),
                nullableNumber(value, "recentVolumeRatio"),
                nullableNumber(value, "contractionRatio"),
                nullableNumber(value, "drawdown120dPct"),
                nullableNumber(value, "ma20GapPct"),
                nullableNumber(value, "recentDrop3dPct"),
                textList(value, "reasons"),
                textList(value, "cautions")
        );
    }

    private static ReversalConfirmation reversal(ObjectValue value) {
        return new ReversalConfirmation(
                ReversalConfirmationStatus.valueOf(requiredText(value, "status")),
                requiredInteger(value, "score"),
                nullableDate(value, "signalDate"),
                requiredText(value, "summary"),
                textList(value, "reasons"),
                textList(value, "cautions")
        );
    }

    private static BottomStructureState structureState(String value) {
        return switch (value) {
            case "바닥 아님" -> BottomStructureState.NOT_BOTTOM;
            case "바닥 시도" -> BottomStructureState.BOTTOM_ATTEMPT;
            case "재시험 구간" -> BottomStructureState.RETEST;
            case "1차 확인" -> BottomStructureState.FIRST_CONFIRMATION;
            case "구조적 바닥 가능" -> BottomStructureState.STRUCTURAL_BOTTOM_POSSIBLE;
            default -> throw new IllegalArgumentException("unsupported legacy bottom structure state");
        };
    }

    private static DeepBottomState deepBottomState(String value) {
        return switch (value) {
            case "미충족" -> DeepBottomState.UNMET;
            case "후보" -> DeepBottomState.CANDIDATE;
            case "확신" -> DeepBottomState.CONVICTION;
            default -> throw new IllegalArgumentException("unsupported legacy deep-bottom state");
        };
    }

    private static BottomActionBias actionBias(String value) {
        return switch (value) {
            case "대기" -> BottomActionBias.WAIT;
            case "관찰 매수" -> BottomActionBias.OBSERVE_BUY;
            case "분할 매수" -> BottomActionBias.SCALE_IN_BUY;
            default -> throw new IllegalArgumentException("unsupported legacy bottom action bias");
        };
    }

    static PriceHistorySummary summary(List<BottomPatternPoint> points) {
        if (points.isEmpty()) return new PriceHistorySummary(0, null, null, null, null);
        return new PriceHistorySummary(
                points.size(),
                points.getFirst().date(),
                points.getFirst().close(),
                points.getLast().date(),
                points.getLast().close()
        );
    }

    private static String requiredText(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(field + " must be non-blank text");
    }

    private static double requiredNumber(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof NumberValue number) {
            var converted = number.value().doubleValue();
            if (Double.isFinite(converted)) return converted;
        }
        throw new IllegalArgumentException(field + " must be a finite number");
    }

    private static Double nullableNumber(ObjectValue object, String field) {
        var value = required(object, field);
        if (value == NullValue.INSTANCE) return null;
        if (value instanceof NumberValue number) {
            var converted = number.value().doubleValue();
            if (Double.isFinite(converted)) return converted;
        }
        throw new IllegalArgumentException(field + " must be a finite number or null");
    }

    private static int requiredInteger(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof NumberValue number) {
            var converted = number.value().doubleValue();
            if (converted == Math.rint(converted) && converted >= Integer.MIN_VALUE && converted <= Integer.MAX_VALUE) {
                return (int) converted;
            }
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static Integer nullableInteger(ObjectValue object, String field) {
        var value = required(object, field);
        if (value == NullValue.INSTANCE) return null;
        return requiredInteger(object, field);
    }

    private static LocalDate requiredDate(ObjectValue object, String field) {
        var value = nullableDate(object, field);
        if (value == null) throw new IllegalArgumentException(field + " must be an ISO date");
        return value;
    }

    private static LocalDate nullableDate(ObjectValue object, String field) {
        var value = required(object, field);
        if (value == NullValue.INSTANCE) return null;
        if (!(value instanceof TextValue text) || text.value().isBlank()) {
            throw new IllegalArgumentException(field + " must be an ISO date or null");
        }
        try {
            return LocalDate.parse(text.value());
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be an ISO date or null", error);
        }
    }

    private static ObjectValue requiredObject(StructuredValue value, String field) {
        if (value instanceof ObjectValue object) return object;
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static ArrayValue requiredArray(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof ArrayValue array) return array;
        throw new IllegalArgumentException(field + " must be an array");
    }

    private static List<String> textList(ObjectValue object, String field) {
        var array = requiredArray(object, field);
        return array.values().stream().map(item -> {
            if (item instanceof TextValue text) return text.value();
            throw new IllegalArgumentException(field + " must contain text only");
        }).toList();
    }

    private static StructuredValue required(ObjectValue object, String field) {
        var value = object.fields().get(field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
