package io.macrosquare.research.domain.rotation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorEarningsRevisionBreadthPolicyTest {

    private final SectorEarningsRevisionBreadthPolicy policy =
            new SectorEarningsRevisionBreadthPolicy();

    @Test
    void scoresNetDirectionalBreadthWithoutTreatingItAsAProbability() {
        var evidence = evidence(10, 10, 7, 2, 1);

        assertEquals(75, policy.score(evidence).orElseThrow());
        assertEquals(100, evidence.coveragePct());
        assertEquals(70, evidence.revisedUpPct());
        assertEquals(20, evidence.revisedDownPct());
    }

    @Test
    void failsClosedWhenCoverageOrAbsoluteCountIsInsufficient() {
        assertTrue(policy.score(evidence(20, 4, 3, 1, 0)).isEmpty());
        assertTrue(policy.score(evidence(20, 8, 5, 2, 1)).isEmpty());
    }

    private static SectorEarningsRevisionBreadth evidence(
            int constituents,
            int covered,
            int up,
            int down,
            int unchanged
    ) {
        return new SectorEarningsRevisionBreadth(
                LocalDate.parse("2026-08-08"),
                LocalDate.parse("2026-08-07"),
                LocalDate.parse("2026-08-08"),
                constituents, covered, up, down, unchanged);
    }
}
