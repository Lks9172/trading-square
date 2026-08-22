package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.CompanyFundamentalsFreshness;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import io.macrosquare.company.domain.model.Ticker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyFundamentalsFreshnessPolicyTest {

    private final CompanyFundamentalsNormalizationPolicy normalization =
            new CompanyFundamentalsNormalizationPolicy();
    private final CompanyFundamentalsFreshnessPolicy policy =
            new CompanyFundamentalsFreshnessPolicy();

    @Test
    void marksFactsCurrentWhenTheyCoverTheNewestPeriodicReport() {
        var result = policy.evaluate(
                fundamentals("2026-06-30"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-08-03"),
                "10-Q"
        );

        assertEquals(CompanyFundamentalsFreshness.Status.CURRENT, result.status());
        assertEquals(0, result.lagDays());
        assertTrue(result.scoreComparable());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void withholdsScoresWhenANewerPeriodicReportHasAlreadyBeenFiled() {
        var result = policy.evaluate(
                fundamentals("2026-03-31"),
                LocalDate.parse("2026-06-30"),
                LocalDate.parse("2026-08-03"),
                "10-Q"
        );

        assertEquals(CompanyFundamentalsFreshness.Status.LAGGING, result.status());
        assertEquals(91, result.lagDays());
        assertFalse(result.scoreComparable());
        assertTrue(result.warnings().getFirst().contains("2026-06-30"));
    }

    @Test
    void distinguishesMissingCoreFactsFromUnknownFilingProvenance() {
        var empty = normalization.normalize(
                new Ticker("NONE"), "1", emptyEvidence(), null, LocalDate.parse("2026-08-06"));

        var incomplete = policy.evaluate(empty, LocalDate.parse("2026-06-30"), null, "10-Q");
        var unknown = policy.evaluate(fundamentals("2026-06-30"), null, null, null);

        assertEquals(CompanyFundamentalsFreshness.Status.INCOMPLETE, incomplete.status());
        assertEquals(CompanyFundamentalsFreshness.Status.UNKNOWN, unknown.status());
        assertNull(unknown.lagDays());
    }

    private io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot fundamentals(String endDate) {
        var evidence = new CompanyFundamentalsEvidence(
                List.of(new FinancialFactPoint(100, "10-K", "FY", endDate)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
        return normalization.normalize(new Ticker("TEST"), "1", evidence, null, LocalDate.parse("2026-08-06"));
    }

    private static CompanyFundamentalsEvidence emptyEvidence() {
        return new CompanyFundamentalsEvidence(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
