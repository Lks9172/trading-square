package io.macrosquare.company.domain.bottom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomPriceContextPolicyTest {

    private final BottomPriceContextPolicy contextPolicy = new BottomPriceContextPolicy(new BottomPatternPolicy());

    @Test
    void restoresTheLegacyPriceVolumeContextAndSignalScores() {
        var context = contextPolicy.evaluate(fixture());

        assertEquals(-21.3, context.drawdownFromHighPct());
        assertNull(context.drawdownFrom120dHighPct());
        assertEquals(18.0, context.reboundFromLowPct());
        assertEquals(2.6, context.return30dPct());
        assertEquals(1.9, context.volumeTrend20dPct());
        assertEquals(3.7, context.ma20GapPct());
        assertTrue(context.ma20Below50());
        assertEquals(-5.5, context.recentDrop3dPct());
        assertEquals(BottomPatternPhase.CONFIRM, context.pattern().phase());
        assertEquals(1.01, context.candidateVolumeRatio());
        assertEquals(1.01, context.confirmVolumeRatio());
        assertEquals(1.01, context.retestVolumeRatio());
        assertEquals(1.0, context.absorptionVolumeVsRecent2dRatio());
        assertEquals(1.0, context.absorptionVolumeVsRecent3dRatio());
        assertEquals(-1.0, context.absorptionDropPct());
        assertEquals(-13.0, context.priorDeclineDropPct());
        assertEquals(0.08, context.absorptionContractionRatio());
        assertEquals(LocalDate.parse("2025-12-16"), context.absorptionDate());
        assertEquals(14, context.daysSinceAbsorption());
        assertEquals(13.5, context.reboundSinceAbsorptionPct());

        var signal = new BottomPriceSignalPolicy().evaluate(context);
        assertEquals(72, signal.priceResetScore());
        assertEquals(82, signal.patternScore());
        assertEquals(66, signal.absorptionScore());
        assertEquals(61, signal.volumeConfirmationScore());
        assertEquals(69, signal.priceBottomScore());
        assertEquals(30, signal.failureRiskScore());
        assertEquals(BottomStructureState.BOTTOM_ATTEMPT, signal.structureState());

        var confirmed = new DeepBottomPolicy().evaluate(context.toDeepBottomEvidence(signal.failureRiskScore()));
        assertEquals(69, confirmed.score());
        assertEquals(DeepBottomState.CANDIDATE, confirmed.state());
    }

    @Test
    @SuppressWarnings("removal")
    void capturedCompanyContextCannotChangeTheCurrentChartSignal() {
        var context = contextPolicy.evaluate(fixture());
        var policy = new BottomPriceSignalPolicy();

        var pure = policy.evaluate(context);

        assertEquals(pure, policy.evaluate(context, 0, 100.0, false));
        assertEquals(pure, policy.evaluate(context, 100, -100.0, true));
    }

    @Test
    void returnsAnExplicitEmptyContextWhenNoHistoryExists() {
        // Invalid/non-positive closes are rejected at the price-point boundary;
        // the policy's empty branch therefore represents a genuinely empty
        // provider history rather than silently filtering corrupt prices.
        var context = contextPolicy.evaluate(java.util.List.of());

        assertTrue(context.chartPoints().isEmpty());
        assertEquals(BottomPatternPhase.DECLINE, context.pattern().phase());
        assertNull(context.absorptionDate());
        assertFalse(context.ma20Below50());
    }

    private static java.util.List<BottomPatternPoint> fixture() {
        double[] closes = {
                100, 101, 102, 103, 104, 105, 106, 107, 108, 109,
                110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
                150, 121, 122, 123, 124, 115, 115, 115, 115, 115,
                100, 102, 104, 106, 107, 110, 113, 116, 118, 119,
                120, 114, 110, 107, 105, 104, 106, 110, 114, 116,
                118, 117, 116, 115, 116, 117, 118, 117, 118, 118
        };
        var date = LocalDate.parse("2025-11-01");
        var points = new ArrayList<BottomPatternPoint>(closes.length);
        for (var index = 0; index < closes.length; index++) {
            points.add(new BottomPatternPoint(date.plusDays(index), closes[index], 1000.0 + index));
        }
        return points;
    }
}
