package io.macrosquare.company.domain.bottom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BottomPatternPolicyTest {

    private final BottomPatternPolicy policy = new BottomPatternPolicy();

    @Test
    void matchesTheExistingTypeScriptConfirmationFixture() {
        var closes = new double[60];
        for (var index = 0; index < closes.length; index++) {
            closes[index] = 100 + index;
        }
        closes[20] = 150;
        for (var index = 25; index < closes.length; index++) {
            closes[index] = 115;
        }
        var tail = new double[]{
                100, 102, 104, 106, 107, 110, 113, 116, 118, 119,
                120, 114, 110, 107, 105, 104, 106, 110, 114, 116,
                118, 117, 116, 115, 116, 117, 118, 117, 118, 118
        };
        System.arraycopy(tail, 0, closes, 30, tail.length);

        var start = LocalDate.of(2025, 11, 1);
        var history = new ArrayList<BottomPatternPoint>();
        for (var index = 0; index < closes.length; index++) {
            history.add(new BottomPatternPoint(start.plusDays(index), closes[index], 1000.0 + index));
        }

        var result = policy.analyze(history);

        assertEquals(BottomPatternPhase.CONFIRM, result.phase());
        assertEquals(LocalDate.of(2025, 11, 21), result.peakPoint().date());
        assertEquals(LocalDate.of(2025, 12, 1), result.candidatePoint().date());
        assertEquals(LocalDate.of(2025, 12, 16), result.retestPoint().date());
        assertEquals(LocalDate.of(2025, 12, 21), result.confirmPoint().date());
        assertEquals(-33.3, result.declinePctFromPeak());
        assertEquals(18.0, result.reboundPctFromCandidate());
        assertEquals(4.0, result.retestGapPct());
    }

    @Test
    void shortHistoryStaysInDeclineWithoutInventingAPattern() {
        var result = policy.analyze(List.of(
                new BottomPatternPoint(LocalDate.of(2026, 1, 1), 100, null)
        ));

        assertEquals(BottomPatternPhase.DECLINE, result.phase());
        assertEquals(100, result.currentPoint().close());
        assertNull(result.candidatePoint());
    }
}
