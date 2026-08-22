package io.macrosquare.company.domain.model;

/** Current transport-neutral analyst recommendation and target-price evidence. */
public record CompanyAnalystConsensus(
        Double analystScore,
        Double upsidePct,
        Double epsEstimateRevision7dPct,
        Double epsEstimateRevision30dPct,
        Double epsEstimateRevision90dPct
) {
    public CompanyAnalystConsensus(Double analystScore, Double upsidePct) {
        this(analystScore, upsidePct, null, null, null);
    }

    public CompanyAnalystConsensus {
        analystScore = finiteOrNull(analystScore, "analystScore");
        if (analystScore != null && (analystScore < -2 || analystScore > 2)) {
            throw new IllegalArgumentException("analystScore must be between -2 and 2");
        }
        upsidePct = finiteOrNull(upsidePct, "upsidePct");
        if (upsidePct != null && (upsidePct < -100 || upsidePct > 1000)) {
            throw new IllegalArgumentException("upsidePct must be between -100 and 1000");
        }
        epsEstimateRevision7dPct = finiteOrNull(epsEstimateRevision7dPct, "epsEstimateRevision7dPct");
        epsEstimateRevision30dPct = finiteOrNull(epsEstimateRevision30dPct, "epsEstimateRevision30dPct");
        epsEstimateRevision90dPct = finiteOrNull(epsEstimateRevision90dPct, "epsEstimateRevision90dPct");
    }

    private static Double finiteOrNull(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite or null");
        }
        return value;
    }
}
