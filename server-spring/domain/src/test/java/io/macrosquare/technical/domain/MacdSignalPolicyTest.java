package io.macrosquare.technical.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacdSignalPolicyTest {

    private final MacdSignalPolicy policy = new MacdSignalPolicy();

    @Test
    void exposesStandardBullishSignalLineCrossWithoutCallingItReturnProbability() {
        var analysis = policy.evaluate(points(100, index -> index < 60 ? 100 : 100 + (index - 60) * .8));

        assertEquals(MacdSignalAnalysis.SignalPosition.ABOVE_SIGNAL, analysis.daily().position());
        assertEquals(MacdSignalAnalysis.CrossType.BULLISH_CROSS, analysis.daily().latestCross());
        assertTrue(analysis.daily().sessionsSinceCross() >= 0);
        assertTrue(analysis.daily().methodology().contains("not a return probability"));
    }

    @Test
    void exposesBearishSignalLineCrossAfterMomentumRollsOver() {
        var analysis = policy.evaluate(points(120, index -> index < 75
                ? 100 + index * .5
                : 137.5 - (index - 75) * 1.1));

        assertEquals(MacdSignalAnalysis.SignalPosition.BELOW_SIGNAL, analysis.daily().position());
        assertEquals(MacdSignalAnalysis.CrossType.BEARISH_CROSS, analysis.daily().latestCross());
        assertTrue(analysis.daily().sessionsSinceCross() >= 0);
    }

    @Test
    void regularBullishDivergenceWaitsForRightSidePivotConfirmation() {
        var closes = new ArrayList<Double>();
        append(closes, 45, i -> 100d);
        append(closes, 14, i -> 100d - i * 1.5);
        append(closes, 10, i -> 80.5 + i * 1.0);
        append(closes, 20, i -> 89.5 - i * .62);
        append(closes, 12, i -> 77.72 + i * .85);

        var full = policy.evaluate(points(closes));
        assertEquals(MacdSignalAnalysis.DivergenceType.BULLISH, full.daily().divergence());
        assertNotNull(full.daily().divergenceConfirmedDate());
        assertTrue(full.daily().divergenceActive());

        var confirmationIndex = indexOf(points(closes), full.daily().divergenceConfirmedDate());
        var beforeConfirmation = policy.evaluate(points(closes.subList(0, confirmationIndex)));
        assertFalse(beforeConfirmation.daily().divergenceActive());
    }

    @Test
    void weeklySeriesIsSeparatelyAggregatedAndLatestPartialWeekIsDisclosed() {
        var daily = points(180, index -> 100 + index * .2);
        var analysis = policy.evaluate(daily);

        assertTrue(analysis.weekly().sourcePointCount() < analysis.daily().sourcePointCount());
        assertEquals(daily.getLast().date(), analysis.weekly().asOf());
        assertEquals(daily.getLast().date().getDayOfWeek().getValue() < 5, analysis.currentWeekProvisional());
    }

    @Test
    void insufficientHistoryFailsClosed() {
        var analysis = policy.evaluate(points(20, index -> 100 + index));

        assertEquals(MacdSignalAnalysis.SignalPosition.UNAVAILABLE, analysis.daily().position());
        assertEquals(MacdSignalAnalysis.DivergenceType.UNAVAILABLE, analysis.daily().divergence());
    }

    private static List<TechnicalClosePoint> points(int count, IntToDoubleFunction values) {
        var result = new ArrayList<TechnicalClosePoint>();
        var start = LocalDate.of(2025, 1, 1);
        for (var index = 0; index < count; index++) {
            result.add(new TechnicalClosePoint(start.plusDays(index), values.applyAsDouble(index)));
        }
        return List.copyOf(result);
    }

    private static List<TechnicalClosePoint> points(List<Double> values) {
        return points(values.size(), values::get);
    }

    private static void append(List<Double> target, int count, IntToDoubleFunction values) {
        for (var index = 0; index < count; index++) target.add(values.applyAsDouble(index));
    }

    private static int indexOf(List<TechnicalClosePoint> points, LocalDate date) {
        for (var index = 0; index < points.size(); index++) {
            if (points.get(index).date().equals(date)) return index;
        }
        throw new IllegalArgumentException("date not found: " + date);
    }
}
