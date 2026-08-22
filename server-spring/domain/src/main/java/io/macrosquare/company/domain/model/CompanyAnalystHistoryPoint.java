package io.macrosquare.company.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/** One persisted daily analyst observation used to calculate revision deltas. */
public record CompanyAnalystHistoryPoint(
        LocalDate date,
        Double analystScore,
        Double upsidePct,
        Double epsEstimateRevision7dPct,
        Double epsEstimateRevision30dPct,
        Double epsEstimateRevision90dPct
) {
    public CompanyAnalystHistoryPoint(LocalDate date, Double analystScore, Double upsidePct) {
        this(date, analystScore, upsidePct, null, null, null);
    }

    public CompanyAnalystHistoryPoint {
        Objects.requireNonNull(date, "date");
        analystScore = finiteOrNull(analystScore, "analystScore");
        if (analystScore != null && (analystScore < -2 || analystScore > 2)) {
            throw new IllegalArgumentException("analystScore must be between -2 and 2");
        }
        upsidePct = finiteOrNull(upsidePct, "upsidePct");
        if (upsidePct != null && (upsidePct < -100 || upsidePct > 1000)) {
            throw new IllegalArgumentException("upsidePct must be between -100 and 1000");
        }
        epsEstimateRevision7dPct = finiteOrNull(
                epsEstimateRevision7dPct, "epsEstimateRevision7dPct");
        epsEstimateRevision30dPct = finiteOrNull(
                epsEstimateRevision30dPct, "epsEstimateRevision30dPct");
        epsEstimateRevision90dPct = finiteOrNull(
                epsEstimateRevision90dPct, "epsEstimateRevision90dPct");
    }

    private static Double finiteOrNull(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite or null");
        }
        return value;
    }
}
