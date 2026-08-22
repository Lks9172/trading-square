package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;

/** Current tracked-constituent participation above 20/50/200-day moving averages. */
public record SectorPriceBreadthEvidence(
        LocalDate asOfDate,
        LocalDate oldestObservedOn,
        LocalDate latestObservedOn,
        int constituentCount,
        int coveredCount,
        int aboveMa20Count,
        int aboveMa50Count,
        int aboveMa200Count,
        int score
) {
    public SectorPriceBreadthEvidence {
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(oldestObservedOn, "oldestObservedOn");
        Objects.requireNonNull(latestObservedOn, "latestObservedOn");
        if (oldestObservedOn.isAfter(latestObservedOn) || latestObservedOn.isAfter(asOfDate)) {
            throw new IllegalArgumentException("breadth dates are invalid");
        }
        if (constituentCount < 1 || coveredCount < 1 || coveredCount > constituentCount) {
            throw new IllegalArgumentException("breadth coverage is invalid");
        }
        if (aboveMa20Count < 0 || aboveMa20Count > coveredCount
                || aboveMa50Count < 0 || aboveMa50Count > coveredCount
                || aboveMa200Count < 0 || aboveMa200Count > coveredCount) {
            throw new IllegalArgumentException("breadth counts are invalid");
        }
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
    }

    public int coveragePct() { return pct(coveredCount, constituentCount); }
    public int aboveMa20Pct() { return pct(aboveMa20Count, coveredCount); }
    public int aboveMa50Pct() { return pct(aboveMa50Count, coveredCount); }
    public int aboveMa200Pct() { return pct(aboveMa200Count, coveredCount); }

    private static int pct(int numerator, int denominator) {
        return (int) Math.round(numerator * 100d / denominator);
    }
}
