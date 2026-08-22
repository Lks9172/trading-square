package io.macrosquare.execution.application.service;

import io.macrosquare.execution.application.model.InvestmentPlanPatch;
import io.macrosquare.execution.application.model.MarketExecutionContext;
import io.macrosquare.execution.application.model.PatchValue;
import io.macrosquare.execution.application.model.TradeLogCommand;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.application.port.out.TrancheRepository;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TrancheEntry;
import io.macrosquare.execution.domain.service.ExecutionPlanPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ManageInvestmentExecutionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T03:04:05.678912Z"), ZoneOffset.UTC);

    @Test
    void enrichesTrancheFromTheCurrentSnapshotAndPersistsIt() {
        var state = new State();
        var service = service(state);

        var result = service.recordTranche("nasdaq", 2, null);

        assertEquals("NASDAQ", result.entry().asset());
        assertEquals(25d, result.entry().weightPct());
        assertEquals(25_500d, result.entry().priceAtEntry());
        assertEquals("RISK_ON", result.entry().regimeAtEntry());
        assertEquals("2026-07-20T03:04:05.678Z", result.entry().executedAt().toString());
        assertEquals(1, result.total());
    }

    @Test
    void recordsHorizonChangesAndRecommendationConflicts() {
        var state = new State();
        var service = service(state);
        var patch = emptyPatch(InvestmentHorizon.LONG);

        var plan = service.updateInvestmentPlan(patch);
        var trade = service.appendTradeLog(new TradeLogCommand(
                TradeLogKind.USER_ACTION, "NASDAQ", null, "SELL", "manual", Map.of()
        ));

        assertEquals(InvestmentHorizon.LONG, plan.horizon());
        assertEquals(2, state.logs.size());
        assertEquals("horizon change: medium → long", state.logs.getFirst().notes());
        assertEquals(true, trade.againstSystemRecommendation());
        assertEquals("RISK_ON", ((io.macrosquare.execution.domain.model.TradeLogValue.TextValue)
                state.logs.getLast().context().get("regimeAtAction")).value());
    }

    @Test
    void clearsOnlyTheExactLegacyAssetKeyAndBoundsReadLimit() {
        var state = new State();
        state.tranches.add(new TrancheEntry("NASDAQ", 1, Instant.parse("2026-07-20T00:00:00Z"), null, null, 30d));
        state.tranches.add(new TrancheEntry("GOLD", 1, Instant.parse("2026-07-20T00:00:00Z"), null, null, 30d));
        var service = service(state);

        assertEquals(1, service.clearTranches("NASDAQ"));
        assertEquals("GOLD", state.tranches.getFirst().asset());
        assertFalse(service.recentTradeLog(-5).iterator().hasNext());
    }

    @Test
    void committedPlanIsNotReportedAsFailedWhenSecondaryAuditStorageIsUnavailable() {
        var state = new State();
        var degradations = new AtomicInteger();
        TradeLogRepository unavailableAudit = new TradeLogRepository() {
            @Override
            public void append(TradeLogEntry entry) {
                throw new IllegalStateException("audit unavailable");
            }

            @Override
            public List<TradeLogEntry> recent(int limit) {
                return List.of();
            }
        };
        var service = new ManageInvestmentExecutionService(
                state,
                unavailableAudit,
                state,
                () -> Optional.empty(),
                new ExecutionPlanPolicy(),
                CLOCK,
                (component, operation, reference, cause) -> degradations.incrementAndGet()
        );

        var saved = service.updateInvestmentPlan(emptyPatch(InvestmentHorizon.LONG));

        assertEquals(InvestmentHorizon.LONG, saved.horizon());
        assertEquals(InvestmentHorizon.LONG, state.load().orElseThrow().horizon());
        assertEquals(1, degradations.get());
    }

    @Test
    void concurrentPartialUpdatesRetainChangesFromBothRequests() throws Exception {
        var state = new State();
        var service = service(state);
        var targetReturnPatch = patch(
                PatchValue.of(22d),
                PatchValue.missing()
        );
        var notesPatch = patch(
                PatchValue.missing(),
                PatchValue.of("keep both fields")
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = List.<Callable<InvestmentPlan>>of(
                    () -> service.updateInvestmentPlan(targetReturnPatch),
                    () -> service.updateInvestmentPlan(notesPatch)
            );
            for (var result : executor.invokeAll(requests)) result.get();
        }

        var saved = state.load().orElseThrow();
        assertEquals(22d, saved.targetReturnAnnualPct());
        assertEquals("keep both fields", saved.notes());
    }

    private static ManageInvestmentExecutionService service(State state) {
        return new ManageInvestmentExecutionService(
                state,
                state,
                state,
                () -> Optional.of(new MarketExecutionContext(
                        "RISK_ON",
                        Map.of("NASDAQ", 25_500d),
                        Map.of("NASDAQ", Map.of(2, 25d)),
                        Map.of("NASDAQ", "STRONG_BUY")
                )),
                new ExecutionPlanPolicy(),
                CLOCK
        );
    }

    private static InvestmentPlanPatch emptyPatch(InvestmentHorizon horizon) {
        return new InvestmentPlanPatch(
                PatchValue.of(horizon),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing()
        );
    }

    private static InvestmentPlanPatch patch(
            PatchValue<Double> targetReturn,
            PatchValue<String> notes
    ) {
        return new InvestmentPlanPatch(
                PatchValue.missing(), targetReturn,
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                PatchValue.missing(), PatchValue.missing(), PatchValue.missing(), PatchValue.missing(),
                notes
        );
    }

    private static final class State implements InvestmentPlanRepository, TradeLogRepository, TrancheRepository {
        private InvestmentPlan plan;
        private final List<TradeLogEntry> logs = new ArrayList<>();
        private final List<TrancheEntry> tranches = new ArrayList<>();

        @Override
        public synchronized Optional<InvestmentPlan> load() {
            return Optional.ofNullable(plan);
        }

        @Override
        public synchronized InvestmentPlan save(InvestmentPlan plan) {
            this.plan = plan;
            return plan;
        }

        @Override
        public synchronized PlanMutation updateAtomically(
                InvestmentPlan initialPlan,
                UnaryOperator<InvestmentPlan> mutation
        ) {
            var before = plan == null ? initialPlan : plan;
            var after = mutation.apply(before);
            plan = after;
            return new PlanMutation(before, after);
        }

        @Override
        public void append(TradeLogEntry entry) {
            logs.add(entry);
        }

        @Override
        public List<TradeLogEntry> recent(int limit) {
            return logs.subList(Math.max(0, logs.size() - limit), logs.size());
        }

        @Override
        public List<TrancheEntry> append(TrancheEntry entry) {
            tranches.add(entry);
            return List.copyOf(tranches);
        }

        @Override
        public List<TrancheEntry> findAll() {
            return List.copyOf(tranches);
        }

        @Override
        public List<TrancheEntry> clearAsset(String asset) {
            tranches.removeIf(entry -> entry.asset().equals(asset));
            return List.copyOf(tranches);
        }
    }
}
