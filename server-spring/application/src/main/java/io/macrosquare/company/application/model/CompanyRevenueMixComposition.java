package io.macrosquare.company.application.model;

import io.macrosquare.company.application.model.CompanyReadModels.Research;

import java.util.Objects;

/**
 * Immutable company-detail projection enriched with direct revenue-mix data.
 *
 * <p>Only the three existing revenue-mix fields may differ from the persisted
 * baseline, and provenance remains outside the public company contract.</p>
 */
public record CompanyRevenueMixComposition(
        Research enrichedDetail,
        CompanyRevenueMixLegacyRead baseline,
        CompanyRevenueMixLegacyRead resolved,
        Source segmentSource,
        Source geographySource
) {
    public CompanyRevenueMixComposition {
        enrichedDetail = Objects.requireNonNull(enrichedDetail, "enrichedDetail");
        baseline = Objects.requireNonNull(baseline, "baseline");
        resolved = Objects.requireNonNull(resolved, "resolved");
        segmentSource = Objects.requireNonNull(segmentSource, "segmentSource");
        geographySource = Objects.requireNonNull(geographySource, "geographySource");
        validateSource(segmentSource, baseline.segment(), resolved.segment(), "segment");
        validateSource(geographySource, baseline.geography(), resolved.geography(), "geography");
    }

    public boolean actualUsed() {
        return segmentSource == Source.DIRECT_SEC_ACTUAL
                || geographySource == Source.DIRECT_SEC_ACTUAL;
    }

    public boolean fallbackUsed() {
        return segmentSource == Source.BASELINE_FALLBACK
                || geographySource == Source.BASELINE_FALLBACK;
    }

    private static void validateSource(
            Source source,
            java.util.List<CompanyRevenueMixLegacyRead.Entry> baseline,
            java.util.List<CompanyRevenueMixLegacyRead.Entry> resolved,
            String category
    ) {
        switch (source) {
            case DIRECT_SEC_ACTUAL -> {
                if (resolved.isEmpty()) throw new IllegalArgumentException(category + " actual source requires values");
            }
            case BASELINE_FALLBACK -> {
                if (baseline.isEmpty() || !baseline.equals(resolved)) {
                    throw new IllegalArgumentException(category + " baseline fallback must preserve values");
                }
            }
            case UNAVAILABLE -> {
                if (!baseline.isEmpty() || !resolved.isEmpty()) {
                    throw new IllegalArgumentException(category + " unavailable source must be empty");
                }
            }
            case REJECTED_BASELINE -> {
                if (baseline.isEmpty() || !resolved.isEmpty()) {
                    throw new IllegalArgumentException(category + " rejected baseline must be nonempty and removed");
                }
            }
        }
    }

    public enum Source {
        DIRECT_SEC_ACTUAL("direct-sec-actual"),
        BASELINE_FALLBACK("legacy-fallback"),
        REJECTED_BASELINE("rejected-legacy-fallback"),
        UNAVAILABLE("unavailable");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
