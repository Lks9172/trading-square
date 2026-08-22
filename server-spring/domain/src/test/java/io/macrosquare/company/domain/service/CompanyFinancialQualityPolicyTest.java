package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;
import io.macrosquare.company.domain.model.Ticker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyFinancialQualityPolicyTest {

    @Test
    void recalculatesQualityFromTheCurrentSnapshotInsteadOfLegacyText() {
        var ticker = new Ticker("TEST");
        var snapshot = new CompanyFundamentalsSnapshot(
                ticker, "0000000001", "2026-08-01",
                100.0, 30.0, 20.0, 22.0, 40.0, 5.0,
                50.0, 20.0, 10.0, 5.0, -2.0, 24.0, 10.0,
                200.0, 165.0, 12.0, 30.0, 2.0, 22.0, -0.35,
                1.65, 7.5, -1.0, 3.0, 25.0, 2.5, 0.1, 0.05,
                18.0, 20.0, false, -0.5, 2.0
        );
        var score = new CompanyScore(ticker, 75,
                new ScoreBreakdown(70, List.of()), new ScoreBreakdown(80, List.of()),
                new ScoreBreakdown(60, List.of()), new ScoreBreakdown(85, List.of()), List.of());

        var result = new CompanyFinancialQualityPolicy().evaluate(snapshot, score);

        assertTrue(result.cashConversionScore() >= 70);
        assertTrue(result.earningsQualityScore() >= 70);
        assertEquals(1.2, result.operatingCashFlowToNetIncome());
    }
}
