package io.macrosquare.execution.application.service;

import io.macrosquare.execution.application.model.InvestmentPlanPatch;
import io.macrosquare.execution.application.model.MarketExecutionContext;
import io.macrosquare.execution.application.model.PatchValue;
import io.macrosquare.execution.application.model.TradeLogCommand;
import io.macrosquare.execution.application.port.in.ManageInvestmentExecutionUseCase;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.LoadMarketExecutionContextPort;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.application.port.out.TrancheRepository;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TradeLogValue;
import io.macrosquare.execution.domain.model.TrancheEntry;
import io.macrosquare.execution.domain.service.ExecutionPlanPolicy;
import io.macrosquare.shared.application.port.out.OperationalEventSink;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ManageInvestmentExecutionService implements ManageInvestmentExecutionUseCase {

    private static final int MAX_TRADE_LOG_LIMIT = 1_000;

    private final InvestmentPlanRepository planRepository;
    private final TradeLogRepository tradeLogRepository;
    private final TrancheRepository trancheRepository;
    private final LoadMarketExecutionContextPort marketContextPort;
    private final ExecutionPlanPolicy policy;
    private final Clock clock;
    private final OperationalEventSink operationalEvents;

    public ManageInvestmentExecutionService(
            InvestmentPlanRepository planRepository,
            TradeLogRepository tradeLogRepository,
            TrancheRepository trancheRepository,
            LoadMarketExecutionContextPort marketContextPort,
            ExecutionPlanPolicy policy,
            Clock clock
    ) {
        this(planRepository, tradeLogRepository, trancheRepository, marketContextPort, policy, clock,
                OperationalEventSink.noop());
    }

    public ManageInvestmentExecutionService(
            InvestmentPlanRepository planRepository,
            TradeLogRepository tradeLogRepository,
            TrancheRepository trancheRepository,
            LoadMarketExecutionContextPort marketContextPort,
            ExecutionPlanPolicy policy,
            Clock clock,
            OperationalEventSink operationalEvents
    ) {
        this.planRepository = Objects.requireNonNull(planRepository);
        this.tradeLogRepository = Objects.requireNonNull(tradeLogRepository);
        this.trancheRepository = Objects.requireNonNull(trancheRepository);
        this.marketContextPort = Objects.requireNonNull(marketContextPort);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.operationalEvents = Objects.requireNonNull(operationalEvents);
    }

    @Override
    public InvestmentPlan investmentPlan() {
        return planRepository.load().orElseGet(() -> InvestmentPlan.defaults(now()));
    }

    @Override
    public InvestmentPlan updateInvestmentPlan(InvestmentPlanPatch patch) {
        Objects.requireNonNull(patch, "patch");
        var changedAt = now();
        var mutation = planRepository.updateAtomically(
                InvestmentPlan.defaults(changedAt),
                current -> apply(current, patch, changedAt)
        );
        var before = mutation.before();
        var saved = mutation.after();
        if (patch.horizon().present() && patch.horizon().value() != before.horizon()) {
            var context = new LinkedHashMap<String, TradeLogValue>();
            context.put("before", new TradeLogValue.TextValue(before.horizon().value()));
            context.put("after", new TradeLogValue.TextValue(saved.horizon().value()));
            try {
                tradeLogRepository.append(new TradeLogEntry(
                        changedAt,
                        TradeLogKind.OBSERVATION,
                        null,
                        null,
                        null,
                        "horizon change: " + before.horizon().value() + " → " + saved.horizon().value(),
                        null,
                        context
                ));
            } catch (RuntimeException error) {
                // The plan aggregate is authoritative. A secondary audit append must not
                // make clients retry an update that has already committed to its store.
                operationalEvents.degraded("investment-execution", "plan-audit", "horizon", error);
            }
        }
        return saved;
    }

    @Override
    public TrancheWriteResult recordTranche(String asset, int stage, Double priceAtEntry) {
        var normalizedAsset = asset == null ? "" : asset.trim().toUpperCase();
        var context = currentContext();
        var price = priceAtEntry != null ? priceAtEntry : context.map(value -> value.price(normalizedAsset)).orElse(null);
        var weight = context.map(value -> value.trancheWeight(normalizedAsset, stage)).orElse(null);
        if (weight == null) weight = policy.fallbackWeight(stage);
        var entry = new TrancheEntry(
                normalizedAsset,
                stage,
                now(),
                price,
                context.map(MarketExecutionContext::regime).orElse(null),
                weight
        );
        var entries = trancheRepository.append(entry);
        return new TrancheWriteResult(entry, entries.size());
    }

    @Override
    public TrancheBook trancheBook() {
        var entries = trancheRepository.findAll();
        return new TrancheBook(entries, policy.summarize(entries));
    }

    @Override
    public int clearTranches(String asset) {
        if (asset == null || asset.isBlank() || asset.length() > 64 || asset.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("asset is invalid");
        }
        return trancheRepository.clearAsset(asset).size();
    }

    @Override
    public List<TradeLogEntry> recentTradeLog(int limit) {
        var bounded = Math.max(0, Math.min(MAX_TRADE_LOG_LIMIT, limit));
        return tradeLogRepository.recent(bounded);
    }

    @Override
    public TradeLogWriteResult appendTradeLog(TradeLogCommand command) {
        Objects.requireNonNull(command, "command");
        var context = new LinkedHashMap<>(command.context());
        Boolean against = null;
        if (command.kind() == TradeLogKind.USER_ACTION && command.asset() != null) {
            var market = currentContext();
            var signal = market.map(value -> value.signal(command.asset())).orElse(null);
            var regime = market.map(MarketExecutionContext::regime).orElse(null);
            against = policy.againstRecommendation(command.to(), signal);
            if (regime != null) context.put("regimeAtAction", new TradeLogValue.TextValue(regime));
            if (signal != null) context.put("signalAtAction", new TradeLogValue.TextValue(signal));
        }
        tradeLogRepository.append(new TradeLogEntry(
                now(),
                command.kind(),
                command.asset(),
                command.from(),
                command.to(),
                command.notes(),
                against,
                context
        ));
        return new TradeLogWriteResult(against);
    }

    private Optional<MarketExecutionContext> currentContext() {
        try {
            return marketContextPort.loadCurrent();
        } catch (RuntimeException error) {
            operationalEvents.degraded("investment-execution", "market-context", "current", error);
            return Optional.empty();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static InvestmentPlan apply(InvestmentPlan current, InvestmentPlanPatch patch, Instant now) {
        return new InvestmentPlan(
                value(patch.horizon(), current.horizon(), false, "horizon"),
                value(patch.targetReturnAnnualPct(), current.targetReturnAnnualPct(), false, "targetReturnAnnualPct"),
                value(patch.maxDrawdownTolerancePct(), current.maxDrawdownTolerancePct(), false, "maxDrawdownTolerancePct"),
                value(patch.rebalanceIntervalDays(), current.rebalanceIntervalDays(), false, "rebalanceIntervalDays"),
                value(patch.leverageMaxPct(), current.leverageMaxPct(), false, "leverageMaxPct"),
                value(patch.profitTakeTargetPct(), current.profitTakeTargetPct(), false, "profitTakeTargetPct"),
                value(patch.stopLossPct(), current.stopLossPct(), false, "stopLossPct"),
                value(patch.monthlyDcaKrw(), current.monthlyDcaKrw(), false, "monthlyDCA_KRW"),
                value(patch.currentHoldings(), current.currentHoldings(), true, "currentHoldings"),
                value(patch.totalCapitalKrw(), current.totalCapitalKrw(), true, "totalCapitalKRW"),
                value(patch.totalCapitalUsd(), current.totalCapitalUsd(), true, "totalCapitalUSD"),
                value(patch.currentHoldingsUsd(), current.currentHoldingsUsd(), true, "currentHoldingsUSD"),
                value(patch.accountStartDate(), current.accountStartDate(), true, "accountStartDate"),
                value(patch.startingCapitalUsd(), current.startingCapitalUsd(), true, "startingCapitalUSD"),
                value(patch.startingCapitalKrw(), current.startingCapitalKrw(), true, "startingCapitalKRW"),
                value(patch.investmentExperienceYears(), current.investmentExperienceYears(), true, "investmentExperienceYears"),
                value(patch.accountType(), current.accountType(), true, "accountType"),
                value(patch.notes(), current.notes(), true, "notes"),
                now
        );
    }

    private static <T> T value(PatchValue<T> patch, T current, boolean nullable, String field) {
        if (!patch.present()) return current;
        if (!nullable && patch.value() == null) throw new IllegalArgumentException(field + " must not be null");
        return patch.value();
    }
}
