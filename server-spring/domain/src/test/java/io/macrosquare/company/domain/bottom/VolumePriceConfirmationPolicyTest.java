package io.macrosquare.company.domain.bottom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumePriceConfirmationPolicyTest {

    private final VolumePriceConfirmationPolicy policy = new VolumePriceConfirmationPolicy();

    @Test
    void confirmsAccumulationWhenPriceVolumeAndRollingVwapRiseTogether() {
        var result = policy.evaluate(trend(true));

        assertEquals(VolumePriceConfirmationState.ACCUMULATION, result.state());
        assertTrue(result.score() >= 70);
        assertTrue(result.closeVsVwap20Pct() > 0);
        assertTrue(result.vwapSlope5dPct() > 0);
        assertTrue(result.obvPressure20Pct() > 0);
        assertEquals(30, result.points().size());
    }

    @Test
    void detectsDistributionWhenDeclinesCarryAllRecentVolume() {
        var result = policy.evaluate(trend(false));

        assertEquals(VolumePriceConfirmationState.DISTRIBUTION, result.state());
        assertTrue(result.score() < 45);
        assertTrue(result.closeVsVwap20Pct() < 0);
        assertTrue(result.obvPressure20Pct() < 0);
    }

    @Test
    void remainsUnavailableInsteadOfFabricatingAThinWindow() {
        var result = policy.evaluate(trend(true).subList(0, 19));
        assertEquals(VolumePriceConfirmationState.UNAVAILABLE, result.state());
        assertEquals(0, result.score());
    }

    private static List<BottomPatternPoint> trend(boolean rising) {
        var result = new ArrayList<BottomPatternPoint>();
        for (var index = 0; index < 30; index++) {
            var close = rising ? 100 + index : 130 - index;
            result.add(new BottomPatternPoint(
                    LocalDate.parse("2026-01-01").plusDays(index),
                    close,
                    1_000.0 + index * 20,
                    close + 1.0,
                    close - 1.0
            ));
        }
        return List.copyOf(result);
    }
}
