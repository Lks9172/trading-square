package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;

/** Dated constituent-level forward-EPS revision breadth for one sector. */
public record SectorEarningsRevisionBreadth(
        LocalDate asOfDate,
        LocalDate oldestObservedOn,
        LocalDate latestObservedOn,
        int constituentCount,
        int coveredCount,
        int revisedUpCount,
        int revisedDownCount,
        int unchangedCount
) {
    public SectorEarningsRevisionBreadth {
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(oldestObservedOn, "oldestObservedOn");
        Objects.requireNonNull(latestObservedOn, "latestObservedOn");
        if (oldestObservedOn.isAfter(latestObservedOn) || latestObservedOn.isAfter(asOfDate)) {
            throw new IllegalArgumentException("revision observation dates are invalid");
        }
        if (constituentCount < 1 || coveredCount < 1 || coveredCount > constituentCount) {
            throw new IllegalArgumentException("revision coverage is invalid");
        }
        if (revisedUpCount < 0 || revisedDownCount < 0 || unchangedCount < 0
                || revisedUpCount + revisedDownCount + unchangedCount != coveredCount) {
            throw new IllegalArgumentException("revision breadth counts are invalid");
        }
    }

    public int coveragePct() {
        return (int) Math.round(coveredCount * 100d / constituentCount);
    }

    public int revisedUpPct() {
        return (int) Math.round(revisedUpCount * 100d / coveredCount);
    }

    public int revisedDownPct() {
        return (int) Math.round(revisedDownCount * 100d / coveredCount);
    }
}
