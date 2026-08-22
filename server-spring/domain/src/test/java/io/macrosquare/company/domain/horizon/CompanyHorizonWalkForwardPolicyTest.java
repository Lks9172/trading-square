package io.macrosquare.company.domain.horizon;

import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPatternPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignalPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomPolicy;
import io.macrosquare.company.domain.bottom.ReversalConfirmationPolicy;
import io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyHorizonWalkForwardPolicyTest {

    @Test
    void producesBoundedCausalMetricsForEachForwardHorizon() {
        var policy = new CompanyHorizonWalkForwardPolicy(
                new BottomPriceContextPolicy(new BottomPatternPolicy()),
                new BottomPriceSignalPolicy(),
                new DeepBottomPolicy(),
                new ReversalConfirmationPolicy(),
                new VolumePriceConfirmationPolicy());
        var history = cyclicalHistory(420);

        var result = policy.evaluate(history);

        assertEquals(history.size(), result.historyPointCount());
        assertEquals(history.getFirst().date(), result.firstDate());
        assertEquals(history.getLast().date(), result.lastDate());
        assertEquals(List.of(20, 63, 126), result.horizons().stream()
                .map(HorizonWalkForwardMetric::forwardTradingDays).toList());
        assertTrue(result.methodology().contains("당시까지"));
        result.horizons().forEach(metric -> {
            if (metric.positiveHitRatePct() != null) {
                assertTrue(metric.positiveHitRatePct() >= 0 && metric.positiveHitRatePct() <= 100);
            }
            if (metric.targetHitRatePct() != null) {
                assertTrue(metric.targetHitRatePct() >= 0 && metric.targetHitRatePct() <= 100);
            }
        });
    }

    private static List<BottomPatternPoint> cyclicalHistory(int count) {
        var result = new ArrayList<BottomPatternPoint>();
        var date = LocalDate.parse("2024-01-01");
        for (var index = 0; index < count; index++) {
            var cycle = index % 105;
            var close = cycle < 55
                    ? 100 + cycle * 0.5
                    : cycle < 72
                    ? 127.5 - (cycle - 55) * 2.0
                    : 93.5 + (cycle - 72) * 1.25;
            var volume = cycle >= 65 && cycle <= 75 ? 3_000.0 : 1_000.0 + cycle * 5;
            result.add(new BottomPatternPoint(date.plusDays(index), close, volume, close + 1, close - 1));
        }
        return List.copyOf(result);
    }
}
