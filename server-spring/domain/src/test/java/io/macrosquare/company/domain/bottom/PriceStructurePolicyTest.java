package io.macrosquare.company.domain.bottom;

import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.BearishReversalStage;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.MovingAverageState;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.PriceLocation;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.TrendState;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceStructurePolicyTest {

    private final PriceStructurePolicy policy = new PriceStructurePolicy();

    @Test
    void identifiesRisingDowStructureAndProducesNativeChannelAndMovingAverages() {
        var result = policy.evaluate(waveTrend(280, 80, 0.28, 4.5, 32));

        assertEquals(TrendState.UPTREND, result.trendState());
        assertTrue(result.score() >= 55);
        assertNotNull(result.channelLower());
        assertNotNull(result.channelMid());
        assertNotNull(result.channelUpper());
        assertTrue(result.channelLower() < result.channelMid());
        assertTrue(result.channelMid() < result.channelUpper());
        assertNotNull(result.sma200());
        assertTrue(result.movingAverageState() == MovingAverageState.BULLISH_ALIGNED
                || result.movingAverageState() == MovingAverageState.TRANSITION);
        assertEquals(260, result.points().size());
    }

    @Test
    void raisesStageThreeWhenCurrentPriceBreaksThePriorSwingLow() {
        var points = new ArrayList<>(waveTrend(230, 100, 0.12, 7, 28));
        var date = points.getLast().date();
        var close = points.getLast().close();
        for (var index = 1; index <= 8; index++) {
            close *= 0.94;
            points.add(point(date.plusDays(index), close, 2_000 + index * 300));
        }

        var result = policy.evaluate(points);

        assertEquals(BearishReversalStage.PRIOR_LOW_BROKEN, result.bearishReversalStage());
        assertEquals(PriceLocation.BREAKDOWN, result.priceLocation());
        assertTrue(result.score() < 35);
        assertTrue(result.cautions().stream().anyMatch(value -> value.contains("3단계")));
    }

    @Test
    void refusesToTreatRsiOversoldAsAStandaloneBuySignal() {
        var points = new ArrayList<BottomPatternPoint>();
        var date = LocalDate.parse("2025-01-01");
        for (var index = 0; index < 230; index++) {
            var close = 220 - index * 0.48;
            points.add(point(date.plusDays(index), close, 1_000));
        }

        var result = policy.evaluate(points);

        assertNotNull(result.rsi14());
        assertTrue(result.rsi14() <= 30);
        assertFalse(result.oversoldConfluence());
        assertTrue(result.cautions().stream().anyMatch(value -> value.contains("단독 매수")));
    }

    @Test
    void requiresVolumeForBreakoutConfirmation() {
        var quiet = rangeWithLastBreakout(1_000);
        var loud = rangeWithLastBreakout(3_000);

        assertFalse(policy.evaluate(quiet).volumeBreakout());
        var confirmed = policy.evaluate(loud);
        assertTrue(confirmed.volumeBreakout());
        assertTrue(confirmed.consolidationDays() >= 40);
    }

    @Test
    void remainsUnavailableForThinHistory() {
        var result = policy.evaluate(waveTrend(40, 100, 0.1, 2, 20));

        assertEquals(TrendState.UNAVAILABLE, result.trendState());
        assertEquals(0, result.score());
        assertTrue(result.methodology().contains("RSI"));
    }

    @Test
    void fibonacciConfluenceContainsOnlyCorroboratingAxesAndNoAutomaticBasePoints() {
        var fibonacci = policy.evaluate(waveTrend(280, 80, 0.28, 4.5, 32)).fibonacci();
        var expected = (fibonacci.weeklyConfluence() ? 35 : 0)
                + (fibonacci.supportResistanceConfluence() ? 40 : 0)
                + (fibonacci.channelConfluence() ? 25 : 0);

        assertEquals(expected, fibonacci.confluenceScore());
        assertTrue(fibonacci.methodology().contains("수익 확률"));
    }

    private static List<BottomPatternPoint> waveTrend(
            int size,
            double base,
            double slope,
            double amplitude,
            int period
    ) {
        var result = new ArrayList<BottomPatternPoint>();
        var date = LocalDate.parse("2024-01-01");
        for (var index = 0; index < size; index++) {
            var close = base + slope * index + amplitude * Math.sin(index * Math.PI * 2 / period);
            result.add(point(date.plusDays(index), close, 1_000 + index));
        }
        return List.copyOf(result);
    }

    private static List<BottomPatternPoint> rangeWithLastBreakout(double lastVolume) {
        var result = new ArrayList<BottomPatternPoint>();
        var date = LocalDate.parse("2025-01-01");
        for (var index = 0; index < 219; index++) {
            var close = 100 + Math.sin(index * Math.PI * 2 / 24) * 2.5;
            result.add(point(date.plusDays(index), close, 1_000));
        }
        result.add(point(date.plusDays(219), 106, lastVolume));
        return List.copyOf(result);
    }

    private static BottomPatternPoint point(LocalDate date, double close, double volume) {
        return new BottomPatternPoint(date, close, volume, close * 1.008, close * 0.992);
    }
}
