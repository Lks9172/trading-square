package io.macrosquare.research.domain.rotation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorWalkForwardBacktestPolicyTest {

    private final SectorWalkForwardBacktestPolicy policy = new SectorWalkForwardBacktestPolicy();

    @Test
    void walksForwardAtMonthEndsUsingTheLiveRiskAdjustedTotalReturnMethod() {
        var input = new LinkedHashMap<String, List<SectorTotalReturnPoint>>();
        input.put(SectorWalkForwardBacktestPolicy.BENCHMARK_KEY, history(2_200, .0002));
        for (var index = 0; index < SectorWalkForwardBacktestPolicy.SECTOR_KEYS.size(); index++) {
            input.put(SectorWalkForwardBacktestPolicy.SECTOR_KEYS.get(index),
                    history(2_200, .0020 - index * .00015));
        }
        var asOf = LocalDate.parse("2024-12-31");

        var result = policy.evaluate(input, asOf, 3);

        assertEquals(SectorWalkForwardBacktestPolicy.METHODOLOGY_VERSION, result.methodologyVersion());
        assertTrue(result.rebalanceCount() >= 35 && result.rebalanceCount() <= 37);
        assertEquals("XLK_TR", result.events().getFirst().top1());
        assertEquals(100, result.horizons().get("oneMonth").top1HitRatePct(), .001);
        assertEquals(100, result.horizons().get("threeMonth").top3HitRatePct(), .001);
        assertTrue(result.horizons().get("sixMonth").sampleCount() < result.rebalanceCount());
        assertTrue(result.comparisonBaselineHorizons().containsKey("sixMonth"));
        assertTrue(result.averageMonthlyTurnoverPct() >= 0);
        assertEquals(100, result.horizons().get("sixMonth").top1PositiveReturnRatePct(), .001);
        assertEquals(5, result.horizons().get("sixMonth").overlapAdjustmentLagMonths());
        assertTrue(result.horizons().get("sixMonth").top1HitRateOverlapAdjusted95LowerPct()
                <= result.horizons().get("sixMonth").top1HitRate95LowerPct());
        assertTrue(result.horizons().get("sixMonth").top1HitRateOverlapAdjusted95UpperPct()
                >= result.horizons().get("sixMonth").top1HitRate95UpperPct());
        assertTrue(result.events().stream().allMatch(event -> !event.forward().isEmpty()));
    }

    @Test
    void rejectsAnIncompleteTotalReturnUniverseInsteadOfSilentlyUsingPriceData() {
        var input = new LinkedHashMap<String, List<SectorTotalReturnPoint>>();
        input.put(SectorWalkForwardBacktestPolicy.BENCHMARK_KEY, history(400, .001));

        assertThrows(IllegalArgumentException.class,
                () -> policy.evaluate(input, LocalDate.parse("2024-12-31"), 3));
    }

    private static List<SectorTotalReturnPoint> history(int count, double dailyGrowth) {
        var values = new ArrayList<SectorTotalReturnPoint>(count);
        var date = LocalDate.parse("2019-01-01");
        double price = 100;
        for (var index = 0; index < count; index++) {
            values.add(new SectorTotalReturnPoint(date.plusDays(index), price));
            price *= 1 + dailyGrowth;
        }
        return List.copyOf(values);
    }
}
