package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import io.macrosquare.company.domain.model.FinancialFactPoint;
import io.macrosquare.company.domain.model.Ticker;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanyFundamentalsContinuityPolicyTest {

    @Test
    void successorCurrentFactsAndPredecessorHistoryProduceOneContinuousTtm() {
        var successor = evidence(List.of(
                annual("2025-12-31", 332.238),
                ytd("2025-01-01", "2025-06-30", 164.636),
                ytd("2026-01-01", "2026-06-30", 201.155)
        ));
        var predecessor = evidence(List.of(
                // A predecessor restatement for the same period must not
                // override the successor-owned current observation.
                annual("2025-12-31", 330.000),
                annual("2024-12-31", 339.247),
                ytd("2025-01-01", "2025-06-30", 164.636)
        ));

        var merged = new CompanyFundamentalsContinuityPolicy().merge(List.of(successor, predecessor));
        var snapshot = new CompanyFundamentalsNormalizationPolicy().normalize(
                new Ticker("XOM"), "0002115436", merged, null, LocalDate.parse("2026-08-06"));

        assertEquals(4, merged.revenue().size());
        assertEquals("2026-06-30", snapshot.asOf());
        assertEquals(368.757, snapshot.revenueTtm(), 1e-9);
    }

    private static CompanyFundamentalsEvidence evidence(List<FinancialFactPoint> revenue) {
        return new CompanyFundamentalsEvidence(
                revenue, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }

    private static FinancialFactPoint annual(String end, double value) {
        return new FinancialFactPoint(value, "10-K", "FY", end, end.substring(0, 4) + "-01-01");
    }

    private static FinancialFactPoint ytd(String start, String end, double value) {
        return new FinancialFactPoint(value, "10-Q", "Q2", end, start);
    }
}
