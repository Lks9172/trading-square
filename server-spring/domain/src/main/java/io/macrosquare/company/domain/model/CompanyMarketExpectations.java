package io.macrosquare.company.domain.model;

public record CompanyMarketExpectations(
        Double estimateUpsidePct,
        Double estimateRevision7d,
        Double estimateRevision30d,
        Double estimateRevision90d,
        Double targetUpsideChange30d,
        Double analystScoreRevision30d
) {
    /** Compatibility constructor: the historical second value is target-upside change, not EPS revision. */
    public CompanyMarketExpectations(
            Double estimateUpsidePct,
            Double targetUpsideChange30d,
            Double analystScoreRevision30d
    ) {
        this(estimateUpsidePct, null, null, null, targetUpsideChange30d, analystScoreRevision30d);
    }

    public CompanyMarketExpectations {
        requireFiniteOrNull(estimateUpsidePct, "estimateUpsidePct");
        requireFiniteOrNull(estimateRevision7d, "estimateRevision7d");
        requireFiniteOrNull(estimateRevision30d, "estimateRevision30d");
        requireFiniteOrNull(estimateRevision90d, "estimateRevision90d");
        requireFiniteOrNull(targetUpsideChange30d, "targetUpsideChange30d");
        requireFiniteOrNull(analystScoreRevision30d, "analystScoreRevision30d");
    }

    private static void requireFiniteOrNull(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite or null");
        }
    }
}
