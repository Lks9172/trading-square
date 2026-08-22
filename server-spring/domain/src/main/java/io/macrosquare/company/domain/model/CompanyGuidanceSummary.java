package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Structured revenue, margin, CAPEX, and free-cash-flow guidance extracted from one document. */
public record CompanyGuidanceSummary(
        Stance stance,
        CompanyGuidanceMetric revenue,
        CompanyGuidanceMetric margin,
        CompanyGuidanceMetric capex,
        CompanyGuidanceMetric freeCashFlow,
        List<String> evidence
) {
    public CompanyGuidanceSummary {
        stance = Objects.requireNonNull(stance, "stance");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.stream().anyMatch(item -> item == null || item.isBlank())) {
            throw new IllegalArgumentException("evidence entries must be non-blank");
        }
    }

    public boolean relevant() {
        return stance != Stance.UNCLEAR
                || Stream.of(revenue, margin, capex, freeCashFlow).anyMatch(Objects::nonNull);
    }

    public int structuredMetricCount() {
        return (int) Stream.of(revenue, margin, capex, freeCashFlow)
                .filter(Objects::nonNull)
                .filter(CompanyGuidanceMetric::structured)
                .count();
    }

    public enum Stance {
        RAISED("raised"),
        LOWERED("lowered"),
        AFFIRMED("affirmed"),
        MIXED("mixed"),
        UNCLEAR("unclear");

        private final String value;

        Stance(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
