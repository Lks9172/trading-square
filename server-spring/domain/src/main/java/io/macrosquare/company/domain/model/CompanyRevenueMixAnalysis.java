package io.macrosquare.company.domain.model;

/** Result of evaluating direct filing evidence; absent categories remain null. */
public record CompanyRevenueMixAnalysis(
        CompanyRevenueMixBreakdown segment,
        CompanyRevenueMixBreakdown geography,
        int sourceDocumentCount,
        int dimensionalFactCount
) {
    public CompanyRevenueMixAnalysis {
        if (sourceDocumentCount < 0 || dimensionalFactCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }

    public boolean hasSegment() {
        return segment != null;
    }

    public boolean hasGeography() {
        return geography != null;
    }
}
