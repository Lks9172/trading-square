package io.macrosquare.market.adapter.out.execution;

import io.macrosquare.execution.domain.model.CurrentExecutionEvidence;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan;
import io.macrosquare.execution.domain.service.CurrentExecutionPlanPolicy;
import io.macrosquare.market.application.model.CurrentMarketDecisionContext;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.BuildCurrentExecutionPlansPort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Outer anti-corruption adapter between market projections and execution domain policy. */
public final class ExecutionPlanBridgeAdapter implements BuildCurrentExecutionPlansPort {

    private final CurrentExecutionPlanPolicy policy;

    public ExecutionPlanBridgeAdapter(CurrentExecutionPlanPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public ArrayValue build(CurrentMarketDecisionContext context) {
        var evidence = new CurrentExecutionEvidence(
                context.regime(), context.regimeScore(), context.rawValues(), context.derivedValues(),
                context.targetAllocations(), context.signals().stream().map(signal ->
                        new CurrentExecutionEvidence.SignalEvidence(
                                signal.asset(),
                                CurrentExecutionEvidence.SignalAction.valueOf(signal.action()),
                                signal.dataCoveragePct(), signal.reasons(), signal.unmetReasons()))
                        .toList());
        return project(policy.evaluate(evidence));
    }

    private static ArrayValue project(List<CurrentExecutionPlan> plans) {
        var values = new ArrayList<StructuredValue>();
        for (var plan : plans) {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("asset", text(plan.asset()));
            fields.put("action", text(plan.action().name()));
            fields.put("actionLabel", text(plan.actionLabel()));
            fields.put("currentPrice", plan.currentPrice() == null ? NullValue.INSTANCE : number(plan.currentPrice()));
            fields.put("targetAllocationPct", number(plan.targetAllocationPct()));
            var stages = new ArrayList<StructuredValue>();
            for (var stage : plan.stages()) {
                var stageFields = new LinkedHashMap<String, StructuredValue>();
                stageFields.put("stage", number(stage.stage()));
                stageFields.put("weightPct", number(stage.weightPct()));
                stageFields.put("triggerCondition", text(stage.triggerCondition()));
                if (stage.triggerPrice() != null) stageFields.put("triggerPrice", number(stage.triggerPrice()));
                stageFields.put("status", text(stage.status().name().toLowerCase(Locale.ROOT)));
                stages.add(new ObjectValue(stageFields));
            }
            fields.put("stages", new ArrayValue(stages));
            fields.put("stopLoss", exitRule(plan.stopLoss()));
            fields.put("takeProfit", exitRule(plan.takeProfit()));
            fields.put("validityDays", number(plan.validityDays()));
            fields.put("primaryReason", text(plan.primaryReason()));
            var timing = new LinkedHashMap<String, StructuredValue>();
            timing.put("macroAligned", new BooleanValue(plan.timing().macroAligned()));
            timing.put("sectorAligned", new BooleanValue(plan.timing().sectorAligned()));
            timing.put("flowConfirmed", new BooleanValue(plan.timing().flowConfirmed()));
            timing.put("chartConfirmed", new BooleanValue(plan.timing().chartConfirmed()));
            timing.put("overheatingRisk", new BooleanValue(plan.timing().overheatingRisk()));
            timing.put("notes", texts(plan.timing().notes()));
            fields.put("timing", new ObjectValue(timing));
            values.add(new ObjectValue(fields));
        }
        return new ArrayValue(values);
    }

    private static ObjectValue exitRule(CurrentExecutionPlan.ExitRule rule) {
        return new ObjectValue(java.util.Map.of(
                "price", rule.price() == null ? NullValue.INSTANCE : number(rule.price()),
                "condition", text(rule.condition())));
    }

    private static TextValue text(String value) { return new TextValue(value); }
    private static NumberValue number(long value) { return new NumberValue(value); }
    private static NumberValue number(double value) { return new NumberValue(BigDecimal.valueOf(value)); }
    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream().map(TextValue::new).map(StructuredValue.class::cast).toList());
    }
}
