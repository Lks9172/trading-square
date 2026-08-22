package io.macrosquare.research.domain.rotation;

import java.util.OptionalInt;

/** Converts dated constituent direction breadth into a bounded, non-probabilistic score. */
public final class SectorEarningsRevisionBreadthPolicy {

    public static final int MIN_COVERED_CONSTITUENTS = 5;
    public static final int MIN_COVERAGE_PCT = 50;

    public OptionalInt score(SectorEarningsRevisionBreadth evidence) {
        if (evidence == null
                || evidence.coveredCount() < MIN_COVERED_CONSTITUENTS
                || evidence.coveragePct() < MIN_COVERAGE_PCT) {
            return OptionalInt.empty();
        }
        var netBreadth = (evidence.revisedUpCount() - evidence.revisedDownCount())
                / (double) evidence.coveredCount();
        return OptionalInt.of(clamp((int) Math.round(50 + netBreadth * 50)));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
