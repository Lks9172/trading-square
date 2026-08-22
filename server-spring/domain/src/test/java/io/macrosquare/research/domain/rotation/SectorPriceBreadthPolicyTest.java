package io.macrosquare.research.domain.rotation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorPriceBreadthPolicyTest {

    private final SectorPriceBreadthPolicy policy = new SectorPriceBreadthPolicy();

    @Test
    void calculatesEqualCountParticipationAcrossMovingAverages() {
        var asOf = LocalDate.of(2026, 8, 7);
        var series = new ArrayList<SectorConstituentPriceSeries>();
        for (var index = 0; index < 8; index++) series.add(series("UP" + index, asOf, true));
        for (var index = 0; index < 2; index++) series.add(series("DOWN" + index, asOf, false));

        var result = policy.evaluate(asOf, series).orElseThrow();

        assertEquals(100, result.coveragePct());
        assertEquals(80, result.aboveMa20Pct());
        assertEquals(80, result.aboveMa50Pct());
        assertEquals(80, result.aboveMa200Pct());
        assertEquals(80, result.score());
    }

    @Test
    void failsClosedBelowCoverageOrWithStalePrices() {
        var asOf = LocalDate.of(2026, 8, 7);
        var series = new ArrayList<SectorConstituentPriceSeries>();
        for (var index = 0; index < 9; index++) series.add(series("LIVE" + index, asOf, true));
        for (var index = 0; index < 4; index++) series.add(series("STALE" + index, asOf.minusDays(8), true));

        assertTrue(policy.evaluate(asOf, series).isEmpty());
    }

    private static SectorConstituentPriceSeries series(String ticker, LocalDate latest, boolean rising) {
        var points = new ArrayList<SectorPricePoint>();
        for (var index = 0; index < 220; index++) {
            var close = rising ? 100d + index : 320d - index;
            points.add(new SectorPricePoint(latest.minusDays(219L - index), close));
        }
        return new SectorConstituentPriceSeries(ticker, List.copyOf(points));
    }
}
