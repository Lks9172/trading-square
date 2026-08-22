package io.macrosquare.company.domain.investment;

import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyInvestmentAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CompanyPriceStructureActionGuardTest {

    private final CompanyPriceStructureActionGuard guard = new CompanyPriceStructureActionGuard();

    @Test
    void structuralCrackWithoutAHighBreakCapsAScoreBuyAtHold() {
        var result = guard.evaluate(CompanyInvestmentAction.STRONG_BUY, structure(
                67,
                PriceStructureAnalysis.BearishReversalStage.STRUCTURAL_CRACK,
                PriceStructureAnalysis.RecoveryStage.REBOUND,
                PriceStructureAnalysis.PriceLocation.SUPPORT_ZONE,
                false,
                FibonacciRetracementAnalysis.ZoneState.UNAVAILABLE
        ));

        assertEquals(CompanyInvestmentAction.HOLD, result.action());
        assertFalse(result.reason().isBlank());
    }

    @Test
    void priorLowBreakWithoutReclaimCannotRemainABuy() {
        var result = guard.evaluate(CompanyInvestmentAction.BUY, structure(
                72,
                PriceStructureAnalysis.BearishReversalStage.PRIOR_LOW_BROKEN,
                PriceStructureAnalysis.RecoveryStage.REBOUND,
                PriceStructureAnalysis.PriceLocation.SUPPORT_ZONE,
                false,
                FibonacciRetracementAnalysis.ZoneState.MODERATE_RETRACEMENT
        ));

        assertEquals(CompanyInvestmentAction.REDUCE, result.action());
    }

    @Test
    void reclaimedPriorLowCanOnlyRemainBuyNotStrongBuy() {
        var result = guard.evaluate(CompanyInvestmentAction.STRONG_BUY, structure(
                55,
                PriceStructureAnalysis.BearishReversalStage.PRIOR_LOW_BROKEN,
                PriceStructureAnalysis.RecoveryStage.REBOUND,
                PriceStructureAnalysis.PriceLocation.SUPPORT_ZONE,
                true,
                FibonacciRetracementAnalysis.ZoneState.MODERATE_RETRACEMENT
        ));

        assertEquals(CompanyInvestmentAction.BUY, result.action());
    }

    @Test
    void brokenLastDefenseCapsEvenAnOtherwiseHealthyStructure() {
        var result = guard.evaluate(CompanyInvestmentAction.BUY, structure(
                80,
                PriceStructureAnalysis.BearishReversalStage.INTACT,
                PriceStructureAnalysis.RecoveryStage.RETEST_HELD,
                PriceStructureAnalysis.PriceLocation.SUPPORT_ZONE,
                false,
                FibonacciRetracementAnalysis.ZoneState.LAST_DEFENSE_BROKEN
        ));

        assertEquals(CompanyInvestmentAction.HOLD, result.action());
    }

    private static PriceStructureAnalysis structure(
            int score,
            PriceStructureAnalysis.BearishReversalStage reversal,
            PriceStructureAnalysis.RecoveryStage recovery,
            PriceStructureAnalysis.PriceLocation location,
            boolean reclaim,
            FibonacciRetracementAnalysis.ZoneState fibonacciState
    ) {
        var fibonacci = new FibonacciRetracementAnalysis(
                FibonacciRetracementAnalysis.SwingDirection.UP_SWING,
                java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2026-07-01"),
                80d, 120d, 50d, 100d, .5,
                List.of(
                        new FibonacciRetracementAnalysis.FibonacciLevel(.236, 110.56, "0.236"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.382, 104.72, "0.382"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.5, 100, "0.500"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.618, 95.28, "0.618"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.786, 88.56, "0.786")
                ),
                .5, 100d, 0d,
                FibonacciRetracementAnalysis.TimeframeReliability.DAILY_ONLY,
                false, false, false, 0, fibonacciState,
                "test", List.of(), "test"
        );
        return new PriceStructureAnalysis(
                score, PriceStructureAnalysis.TrendState.TRANSITION, reversal, recovery, location,
                PriceStructureAnalysis.MovingAverageState.CONVERGED,
                45d, 100d, 100d, 100d, 100d, 0d,
                90d, 100d, 110d, 50d, 0d,
                null, null, 0, null,
                false, reclaim, false, fibonacci,
                List.of(), List.of(), "test", List.of()
        );
    }
}
