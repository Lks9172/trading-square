package io.macrosquare.research.domain.rotation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorFundFlowPolicyTest {

    private final SectorFundFlowPolicy policy = new SectorFundFlowPolicy();

    @Test
    void derivesCreationFlowFromOfficialSharesOutstandingChanges() {
        var points = new ArrayList<SectorFundHistoryPoint>();
        var date = LocalDate.of(2026, 7, 1);
        for (var index = 0; index < 21; index++) {
            var nav = 100d + index;
            var shares = 1_000_000d + index * 10_000d;
            points.add(new SectorFundHistoryPoint(date.plusDays(index), nav, shares, nav * shares));
        }

        var result = policy.evaluate(points).orElseThrow();

        assertEquals(date.plusDays(20), result.observedOn());
        assertEquals(10_000d * 120d, result.flow1dUsd());
        assertTrue(result.flow5dUsd() > 0);
        assertTrue(result.flow20dUsd() > result.flow5dUsd());
        assertTrue(result.flow20dPct() > 0);
        assertTrue(result.score() > 50);
    }

    @Test
    void requiresTwentyIntervalsAndPreservesOutflowDirection() {
        var shortHistory = new ArrayList<SectorFundHistoryPoint>();
        for (var index = 0; index < 20; index++) {
            shortHistory.add(point(index, 1_000_000d - index * 5_000d));
        }
        assertTrue(policy.evaluate(shortHistory).isEmpty());

        shortHistory.add(point(20, 900_000d));
        var result = policy.evaluate(shortHistory).orElseThrow();
        assertTrue(result.flow5dUsd() < 0);
        assertTrue(result.flow20dUsd() < 0);
        assertTrue(result.score() < 50);
    }

    private static SectorFundHistoryPoint point(int index, double shares) {
        var nav = 100d;
        return new SectorFundHistoryPoint(
                LocalDate.of(2026, 7, 1).plusDays(index), nav, shares, nav * shares);
    }
}
