package io.macrosquare.company.domain.bottom;

public record BottomPatternAnalysis(
        BottomPatternPoint peakPoint,
        BottomPatternPoint candidatePoint,
        BottomPatternPoint retestPoint,
        BottomPatternPoint confirmPoint,
        BottomPatternPoint currentPoint,
        BottomPatternPhase phase,
        Double declinePctFromPeak,
        Double reboundPctFromCandidate,
        Double retestGapPct
) {
}
