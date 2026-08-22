package io.macrosquare.company.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Transport-neutral current consensus and its persisted daily observations.
 * Collection, JSON, filesystem, and cache-envelope concerns stay outside the domain.
 */
public record CompanyAnalystEvidence(
        Double currentAnalystScore,
        Double currentUpsidePct,
        Double epsEstimateRevision7dPct,
        Double epsEstimateRevision30dPct,
        Double epsEstimateRevision90dPct,
        List<CompanyAnalystHistoryPoint> history
) {
    public CompanyAnalystEvidence(
            Double currentAnalystScore,
            Double currentUpsidePct,
            List<CompanyAnalystHistoryPoint> history
    ) {
        this(currentAnalystScore, currentUpsidePct, null, null, null, history);
    }

    public CompanyAnalystEvidence {
        currentAnalystScore = finiteOrNull(currentAnalystScore, "currentAnalystScore");
        if (currentAnalystScore != null && (currentAnalystScore < -2 || currentAnalystScore > 2)) {
            throw new IllegalArgumentException("currentAnalystScore must be between -2 and 2");
        }
        currentUpsidePct = finiteOrNull(currentUpsidePct, "currentUpsidePct");
        epsEstimateRevision7dPct = finiteOrNull(epsEstimateRevision7dPct, "epsEstimateRevision7dPct");
        epsEstimateRevision30dPct = finiteOrNull(epsEstimateRevision30dPct, "epsEstimateRevision30dPct");
        epsEstimateRevision90dPct = finiteOrNull(epsEstimateRevision90dPct, "epsEstimateRevision90dPct");
        history = Objects.requireNonNull(history, "history").stream()
                .sorted(Comparator.comparing(CompanyAnalystHistoryPoint::date))
                .toList();
    }

    private static Double finiteOrNull(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite or null");
        }
        return value;
    }
}
