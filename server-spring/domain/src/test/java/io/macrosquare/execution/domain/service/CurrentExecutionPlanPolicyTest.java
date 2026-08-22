package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.CurrentExecutionEvidence;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentExecutionPlanPolicyTest {

    private final CurrentExecutionPlanPolicy policy = new CurrentExecutionPlanPolicy();

    @Test
    void requiresPriceStructureBeforePromotingStrongBuyToBuyNow() {
        var plans = policy.evaluate(evidence(
                Map.of("COPPER", 5.25),
                Map.of("SECTOR_XLI", 2.1),
                List.of(signal("COPPER", CurrentExecutionEvidence.SignalAction.STRONG_BUY, 100))));

        var copper = plans.getFirst();
        assertEquals(CurrentExecutionPlan.Action.SCALE_IN, copper.action());
        assertEquals(3, copper.stages().size());
        assertNull(copper.stages().get(1).triggerPrice());
        assertTrue(copper.stages().get(1).triggerCondition().contains("자체 가격·거래량"));
        assertTrue(copper.stages().stream().noneMatch(stage -> stage.status().name().equals("FILLED")));
    }

    @Test
    void promotesOnlyFullyCoveredAndChartConfirmedStrongBuy() {
        var derived = Map.of(
                "SECTOR_XLK", 3.0,
                "NASDAQ_STRUCTURE_SCORE", 72.0,
                "NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0.0,
                "NASDAQ_SMA200", 18_500.0,
                "NASDAQ_FIB_618", 17_200.0,
                "INSTITUTIONAL_SECTOR_TECH_FLOW", 1.0
        );
        var plans = policy.evaluate(evidence(
                Map.of("NASDAQ", 20_000.0), derived,
                List.of(signal("NASDAQ", CurrentExecutionEvidence.SignalAction.STRONG_BUY, 92))));

        var nasdaq = plans.getFirst();
        assertEquals(CurrentExecutionPlan.Action.BUY_NOW, nasdaq.action());
        assertEquals(CurrentExecutionPlan.StageStatus.READY, nasdaq.stages().getFirst().status());
        assertEquals(18_500.0, nasdaq.stages().get(1).triggerPrice());
        assertTrue(nasdaq.timing().flowConfirmed());
    }

    @Test
    void requiresIndependentFlowConfirmationBeforeBuyNow() {
        var derived = Map.of(
                "SECTOR_XLK", 3.0,
                "NASDAQ_STRUCTURE_SCORE", 72.0,
                "NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0.0
        );
        var plans = policy.evaluate(evidence(
                Map.of("NASDAQ", 20_000.0), derived,
                List.of(signal("NASDAQ", CurrentExecutionEvidence.SignalAction.STRONG_BUY, 92))));

        assertEquals(CurrentExecutionPlan.Action.SCALE_IN, plans.getFirst().action());
        assertEquals(CurrentExecutionPlan.StageStatus.PENDING,
                plans.getFirst().stages().getFirst().status());
        assertTrue(plans.getFirst().stages().getFirst().triggerCondition().contains("수급"));
        assertTrue(plans.getFirst().timing().notes().contains("독립 수급 확인 전"));
    }

    @Test
    void ordinaryBuyDoesNotPresentTheFirstTrancheAsImmediatelyExecutable() {
        var derived = Map.of(
                "SECTOR_XLK", 3.0,
                "NASDAQ_STRUCTURE_SCORE", 72.0,
                "NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0.0,
                "INSTITUTIONAL_SECTOR_TECH_FLOW", 1.0
        );
        var plan = policy.evaluate(evidence(
                Map.of("NASDAQ", 20_000.0), derived,
                List.of(signal("NASDAQ", CurrentExecutionEvidence.SignalAction.BUY, 92))))
                .getFirst();

        assertEquals(CurrentExecutionPlan.Action.SCALE_IN, plan.action());
        assertEquals(CurrentExecutionPlan.StageStatus.PENDING, plan.stages().getFirst().status());
        assertNull(plan.stages().getFirst().triggerPrice());
        assertTrue(plan.stages().getFirst().triggerCondition().contains("STRONG BUY"));
    }

    @Test
    void bondVigilanteRegimeBlocksImmediateRiskAssetEntry() {
        var derived = Map.of(
                "SECTOR_XLK", 3.0,
                "NASDAQ_STRUCTURE_SCORE", 72.0,
                "NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0.0,
                "INSTITUTIONAL_SECTOR_TECH_FLOW", 1.0
        );
        var input = evidence(
                Map.of("NASDAQ", 20_000.0), derived,
                List.of(signal("NASDAQ", CurrentExecutionEvidence.SignalAction.STRONG_BUY, 92)));
        var plan = policy.evaluate(new CurrentExecutionEvidence(
                "BOND_VIGILANTE", 70, input.rawValues(), input.derivedValues(),
                input.targetAllocations(), input.signals())).getFirst();

        assertEquals(CurrentExecutionPlan.Action.SCALE_IN, plan.action());
        assertEquals(CurrentExecutionPlan.StageStatus.PENDING, plan.stages().getFirst().status());
        assertTrue(plan.stages().getFirst().triggerCondition().contains("거시"));
    }

    @Test
    void keepsFirstTranchePendingWhileMarketIsOverheated() {
        var derived = Map.of(
                "SECTOR_XLK", 3.0,
                "NASDAQ_STRUCTURE_SCORE", 72.0,
                "NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0.0,
                "INSTITUTIONAL_SECTOR_TECH_FLOW", 1.0,
                "OVERHEATED", 1.0
        );
        var plans = policy.evaluate(evidence(
                Map.of("NASDAQ", 20_000.0), derived,
                List.of(signal("NASDAQ", CurrentExecutionEvidence.SignalAction.STRONG_BUY, 92))));

        assertEquals(CurrentExecutionPlan.Action.SCALE_IN, plans.getFirst().action());
        assertEquals(CurrentExecutionPlan.StageStatus.PENDING, plans.getFirst().stages().getFirst().status());
        assertNull(plans.getFirst().stages().getFirst().triggerPrice());
        assertTrue(plans.getFirst().stages().getFirst().triggerCondition().contains("추격 대기"));
    }

    @Test
    void neverTurnsHoldLeverageIntoAnExecutableTranche() {
        var plans = policy.evaluate(evidence(
                Map.of("TQQQ", 80.0), Map.of(),
                List.of(signal("LEVERAGE", CurrentExecutionEvidence.SignalAction.HOLD, 100))));

        assertEquals(CurrentExecutionPlan.Action.AVOID, plans.getFirst().action());
        assertTrue(plans.getFirst().stages().isEmpty());
    }

    private static CurrentExecutionEvidence evidence(
            Map<String, Double> raw,
            Map<String, Double> derived,
            List<CurrentExecutionEvidence.SignalEvidence> signals
    ) {
        return new CurrentExecutionEvidence(
                "RISK_ON", 70, raw, derived,
                Map.of("nasdaq", 40, "copper", 10, "leverage", 0), signals);
    }

    private static CurrentExecutionEvidence.SignalEvidence signal(
            String asset,
            CurrentExecutionEvidence.SignalAction action,
            int coverage
    ) {
        return new CurrentExecutionEvidence.SignalEvidence(
                asset, action, coverage, List.of("검증 근거"), List.of());
    }
}
