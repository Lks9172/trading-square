package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;

/** Bounded semantic evidence extracted from one official filing document. */
public record CompanyRevenueMixEvidence(
        String source,
        List<CompanyRevenueMixFact> facts,
        List<CompanyRevenueTotal> consolidatedRevenue
) {
    public CompanyRevenueMixEvidence {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        source = source.trim();
        facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
        consolidatedRevenue = List.copyOf(Objects.requireNonNull(
                consolidatedRevenue, "consolidatedRevenue"
        ));
    }

    public boolean hasDimensionalRevenue() {
        return !facts.isEmpty();
    }
}
