package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixFact;
import io.macrosquare.company.domain.model.CompanyRevenueTotal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyRevenueMixPolicyTest {

    private final CompanyRevenueMixPolicy policy = new CompanyRevenueMixPolicy();

    @Test
    void removesOverlappingParentFactsAndKeepsTheMostDetailedAdditiveSubset() {
        var evidence = evidence(
                "annual",
                List.of(
                        fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Product", 75),
                        fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Services", 25),
                        fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Phone", 50),
                        fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Mac", 15),
                        fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Tablet", 10),
                        fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Services", 25)
                ),
                total(100)
        );

        var result = policy.evaluate(List.of(evidence));

        assertTrue(result.hasSegment());
        assertEquals(List.of("Phone", "Services", "Mac", "Tablet"),
                result.segment().entries().stream().map(entry -> entry.label()).toList());
        assertEquals(new BigDecimal("100.0"), result.segment().coveragePercent());
        assertEquals(new BigDecimal("100.0"), result.segment().entries().stream()
                .map(entry -> entry.percentOfTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void prefersReportableSegmentsOverProductDetailForTheSamePeriod() {
        var facts = List.of(
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Cloud", 60),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Consumer", 40),
                fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Product A", 45),
                fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Product B", 35),
                fact(CompanyRevenueMixDimension.PRODUCT_OR_SERVICE, "Services", 20)
        );

        var result = policy.evaluate(List.of(evidence("annual", facts, total(100))));

        assertEquals(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, result.segment().dimension());
        assertEquals(List.of("Cloud", "Consumer"),
                result.segment().entries().stream().map(entry -> entry.label()).toList());
    }

    @Test
    void reclassifiesAnAllRegionalBusinessAxisAsGeography() {
        var facts = List.of(
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Americas", 45),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Europe", 25),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Greater China", 18),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Japan", 7),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Rest of Asia Pacific", 5)
        );

        var result = policy.evaluate(List.of(evidence("annual", facts, total(100))));

        assertNull(result.segment());
        assertTrue(result.hasGeography());
        assertEquals(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, result.geography().dimension());
    }

    @Test
    void keepsMixedGeographicAndBusinessReportableSegmentsAsSegments() {
        var facts = List.of(
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "North America", 60),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "International", 20),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Cloud Services", 20)
        );

        var result = policy.evaluate(List.of(evidence("annual", facts, total(100))));

        assertTrue(result.hasSegment());
        assertFalse(result.hasGeography());
    }

    @Test
    void keepsBrandedRegionalBusinessSegmentsAsSegments() {
        var facts = List.of(
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Walmart US", 67),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Walmart International", 20),
                fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Sams Club US", 13)
        );

        var result = policy.evaluate(List.of(evidence("quarter", facts, total(100))));

        assertTrue(result.hasSegment());
        assertFalse(result.hasGeography());
        assertEquals(List.of("Walmart US", "Walmart International", "Sams Club US"),
                result.segment().entries().stream().map(entry -> entry.label()).toList());
    }

    @Test
    void selectsTheNewestAvailablePeriodForEachCategory() {
        var oldStart = LocalDate.parse("2024-01-01");
        var oldEnd = LocalDate.parse("2024-12-31");
        var newStart = LocalDate.parse("2025-01-01");
        var newEnd = LocalDate.parse("2025-03-31");
        var annual = new CompanyRevenueMixEvidence(
                "annual",
                List.of(
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Cloud", 60, oldStart, oldEnd),
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Consumer", 40, oldStart, oldEnd),
                        fact(CompanyRevenueMixDimension.GEOGRAPHY, "United States", 70, oldStart, oldEnd),
                        fact(CompanyRevenueMixDimension.GEOGRAPHY, "International", 30, oldStart, oldEnd)
                ),
                List.of(total(100, oldStart, oldEnd))
        );
        var quarter = new CompanyRevenueMixEvidence(
                "quarter",
                List.of(
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Cloud", 65, newStart, newEnd),
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Consumer", 35, newStart, newEnd)
                ),
                List.of(total(100, newStart, newEnd))
        );

        var result = policy.evaluate(List.of(annual, quarter));

        assertEquals(newEnd, result.segment().periodEnd());
        assertEquals(oldEnd, result.geography().periodEnd());
    }

    @Test
    void rejectsAGroupThatCannotReconcileToConsolidatedRevenue() {
        var result = policy.evaluate(List.of(evidence(
                "annual",
                List.of(
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Cloud", 20),
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Consumer", 10)
                ),
                total(100)
        )));

        assertFalse(result.hasSegment());
        assertFalse(result.hasGeography());
    }

    @Test
    void validatesEvidenceAndResultBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new CompanyRevenueMixFact(
                CompanyRevenueMixDimension.GEOGRAPHY,
                "Geography",
                "United States",
                BigDecimal.ZERO,
                "USD",
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-12-31")
        ));
        assertThrows(IllegalArgumentException.class, () -> new CompanyRevenueMixEvidence(
                " ", List.of(), List.of()
        ));
    }

    private static CompanyRevenueMixEvidence evidence(
            String source,
            List<CompanyRevenueMixFact> facts,
            CompanyRevenueTotal total
    ) {
        return new CompanyRevenueMixEvidence(source, facts, List.of(total));
    }

    private static CompanyRevenueMixFact fact(
            CompanyRevenueMixDimension dimension,
            String label,
            int value
    ) {
        return fact(
                dimension, label, value,
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")
        );
    }

    private static CompanyRevenueMixFact fact(
            CompanyRevenueMixDimension dimension,
            String label,
            int value,
            LocalDate start,
            LocalDate end
    ) {
        return new CompanyRevenueMixFact(
                dimension,
                switch (dimension) {
                    case REPORTABLE_SEGMENT -> "Statement Business Segments";
                    case PRODUCT_OR_SERVICE -> "Product or Service";
                    case GEOGRAPHY -> "Statement Geographical";
                },
                label,
                BigDecimal.valueOf(value),
                "USD",
                start,
                end
        );
    }

    private static CompanyRevenueTotal total(int value) {
        return total(
                value, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31")
        );
    }

    private static CompanyRevenueTotal total(int value, LocalDate start, LocalDate end) {
        return new CompanyRevenueTotal(BigDecimal.valueOf(value), "USD", start, end);
    }
}
