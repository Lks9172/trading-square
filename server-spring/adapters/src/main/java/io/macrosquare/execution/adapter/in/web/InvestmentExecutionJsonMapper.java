package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.model.InvestmentPlanPatch;
import io.macrosquare.execution.application.model.PatchValue;
import io.macrosquare.execution.application.model.TradeLogCommand;
import io.macrosquare.execution.domain.model.AssetTrancheSummary;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TradeLogValue;
import io.macrosquare.execution.domain.model.TrancheEntry;
import io.macrosquare.execution.domain.service.PortfolioAllocationPolicy;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class InvestmentExecutionJsonMapper {

    private InvestmentExecutionJsonMapper() {
    }

    static InvestmentPlanPatch planPatch(JsonNode root) {
        requireObject(root, "investment plan patch");
        return new InvestmentPlanPatch(
                patch(root, "horizon", node -> InvestmentHorizon.from(text(node, "horizon"))),
                patch(root, "targetReturnAnnualPct", node -> decimal(node, "targetReturnAnnualPct")),
                patch(root, "maxDrawdownTolerancePct", node -> decimal(node, "maxDrawdownTolerancePct")),
                patch(root, "rebalanceIntervalDays", node -> integer(node, "rebalanceIntervalDays")),
                patch(root, "leverageMaxPct", node -> decimal(node, "leverageMaxPct")),
                patch(root, "profitTakeTargetPct", node -> decimal(node, "profitTakeTargetPct")),
                patch(root, "stopLossPct", node -> decimal(node, "stopLossPct")),
                patch(root, "monthlyDCA_KRW", node -> longValue(node, "monthlyDCA_KRW")),
                patch(root, "currentHoldings", InvestmentExecutionJsonMapper::allocation),
                patch(root, "totalCapitalKRW", node -> longValue(node, "totalCapitalKRW")),
                patch(root, "totalCapitalUSD", node -> decimal(node, "totalCapitalUSD")),
                patch(root, "currentHoldingsUSD", InvestmentExecutionJsonMapper::allocation),
                patch(root, "accountStartDate", node -> LocalDate.parse(text(node, "accountStartDate"))),
                patch(root, "startingCapitalUSD", node -> decimal(node, "startingCapitalUSD")),
                patch(root, "startingCapitalKRW", node -> longValue(node, "startingCapitalKRW")),
                patch(root, "investmentExperienceYears", node -> decimal(node, "investmentExperienceYears")),
                patch(root, "accountType", node -> text(node, "accountType")),
                patch(root, "notes", node -> text(node, "notes"))
        );
    }

    static TradeLogCommand tradeLogCommand(JsonNode root) {
        requireObject(root, "trade log request");
        var kindNode = root.get("kind");
        var kind = TradeLogKind.from(kindNode == null || kindNode.isNull() ? null : text(kindNode, "kind"));
        var context = new LinkedHashMap<String, TradeLogValue>();
        var contextNode = root.get("context");
        if (contextNode != null && !contextNode.isNull()) {
            requireObject(contextNode, "context");
            contextNode.properties().forEach(entry -> context.put(entry.getKey(), tradeLogValue(entry.getValue(), 0)));
        }
        return new TradeLogCommand(
                kind,
                optionalText(root.get("asset"), "asset"),
                optionalText(root.get("from"), "from"),
                optionalText(root.get("to"), "to"),
                optionalText(root.get("notes"), "notes"),
                context
        );
    }

    static LinkedHashMap<String, Object> planResponse(
            InvestmentPlan plan,
            PortfolioAllocationPolicy allocationPolicy
    ) {
        var value = new LinkedHashMap<String, Object>();
        value.put("horizon", plan.horizon().value());
        value.put("targetReturnAnnualPct", number(plan.targetReturnAnnualPct()));
        value.put("maxDrawdownTolerancePct", number(plan.maxDrawdownTolerancePct()));
        value.put("rebalanceIntervalDays", plan.rebalanceIntervalDays());
        value.put("leverageMaxPct", number(plan.leverageMaxPct()));
        value.put("profitTakeTargetPct", number(plan.profitTakeTargetPct()));
        value.put("stopLossPct", number(plan.stopLossPct()));
        value.put("monthlyDCA_KRW", plan.monthlyDcaKrw());
        var holdings = allocationPolicy.assess(plan);
        if (!holdings.percentages().isEmpty()) {
            put(value, "currentHoldings", holdings.percentages());
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("sourceUnit", holdings.sourceUnit().name());
            metadata.put("normalized", holdings.normalized());
            metadata.put("sourceTotal", number(holdings.sourceTotal()));
            metadata.put("denominator", number(holdings.denominator()));
            metadata.put("allocatedPct", number(holdings.allocatedPct()));
            metadata.put("unallocatedPct", number(holdings.unallocatedPct()));
            metadata.put("overAllocatedPct", number(holdings.overAllocatedPct()));
            metadata.put("sourceValues", holdings.sourceValues());
            metadata.put("cautions", holdings.cautions());
            value.put("currentHoldingsMeta", metadata);
        }
        put(value, "totalCapitalKRW", plan.totalCapitalKrw());
        put(value, "totalCapitalUSD", nullableNumber(plan.totalCapitalUsd()));
        put(value, "currentHoldingsUSD", plan.currentHoldingsUsd());
        put(value, "accountStartDate", plan.accountStartDate() == null ? null : plan.accountStartDate().toString());
        put(value, "startingCapitalUSD", nullableNumber(plan.startingCapitalUsd()));
        put(value, "startingCapitalKRW", plan.startingCapitalKrw());
        put(value, "investmentExperienceYears", nullableNumber(plan.investmentExperienceYears()));
        put(value, "accountType", plan.accountType());
        put(value, "notes", plan.notes());
        value.put("updatedAt", plan.updatedAt().toString());
        return value;
    }

    static LinkedHashMap<String, Object> trancheResponse(TrancheEntry entry) {
        var value = new LinkedHashMap<String, Object>();
        value.put("asset", entry.asset());
        value.put("stage", entry.stage());
        value.put("executedAt", entry.executedAt().toString());
        value.put("priceAtEntry", nullableNumber(entry.priceAtEntry()));
        value.put("regimeAtEntry", entry.regimeAtEntry());
        if (entry.weightPct() != null) value.put("weightPct", number(entry.weightPct()));
        return value;
    }

    static LinkedHashMap<String, Object> trancheSummaryResponse(AssetTrancheSummary summary) {
        var value = new LinkedHashMap<String, Object>();
        value.put("asset", summary.asset());
        value.put("executedStages", summary.executedStages());
        value.put("nextStage", summary.nextStage());
        value.put("latestRegime", summary.latestRegime());
        value.put("latestExecutedAt", summary.latestExecutedAt() == null ? null : summary.latestExecutedAt().toString());
        return value;
    }

    static LinkedHashMap<String, Object> tradeLogResponse(TradeLogEntry entry) {
        var value = new LinkedHashMap<String, Object>();
        value.put("ts", entry.timestamp().toString());
        value.put("kind", entry.kind().value());
        put(value, "asset", entry.asset());
        put(value, "from", entry.from());
        put(value, "to", entry.to());
        put(value, "notes", entry.notes());
        put(value, "againstSystemRecommendation", entry.againstSystemRecommendation());
        if (!entry.context().isEmpty()) value.put("context", tradeLogObject(entry.context()));
        return value;
    }

    private static <T> PatchValue<T> patch(JsonNode root, String field, Function<JsonNode, T> converter) {
        if (!root.has(field)) return PatchValue.missing();
        var node = root.get(field);
        if (node == null || node.isNull()) return PatchValue.of(null);
        return PatchValue.of(converter.apply(node));
    }

    private static Map<String, Double> allocation(JsonNode node) {
        requireObject(node, "allocation");
        var result = new LinkedHashMap<String, Double>();
        node.properties().forEach(entry -> result.put(entry.getKey(), decimal(entry.getValue(), entry.getKey())));
        return result;
    }

    private static TradeLogValue tradeLogValue(JsonNode node, int depth) {
        if (depth > 8) throw new IllegalArgumentException("trade log context is too deep");
        if (node == null || node.isNull()) return TradeLogValue.NullValue.INSTANCE;
        if (node.isString()) return new TradeLogValue.TextValue(node.stringValue());
        if (node.isBoolean()) return new TradeLogValue.BooleanValue(node.booleanValue());
        if (node.isIntegralNumber()) return new TradeLogValue.NumberValue(node.longValue());
        if (node.isNumber()) return new TradeLogValue.NumberValue(node.decimalValue());
        if (node.isArray()) {
            var result = new ArrayList<TradeLogValue>();
            node.forEach(value -> result.add(tradeLogValue(value, depth + 1)));
            return new TradeLogValue.ArrayValue(result);
        }
        if (node.isObject()) {
            var result = new LinkedHashMap<String, TradeLogValue>();
            node.properties().forEach(entry -> result.put(entry.getKey(), tradeLogValue(entry.getValue(), depth + 1)));
            return new TradeLogValue.ObjectValue(result);
        }
        throw new IllegalArgumentException("unsupported trade log context value");
    }

    private static Object tradeLogValue(TradeLogValue value) {
        return switch (value) {
            case TradeLogValue.TextValue text -> text.value();
            case TradeLogValue.NumberValue number -> number.value();
            case TradeLogValue.BooleanValue bool -> bool.value();
            case TradeLogValue.NullValue ignored -> null;
            case TradeLogValue.ArrayValue array -> array.values().stream()
                    .map(InvestmentExecutionJsonMapper::tradeLogValue).toList();
            case TradeLogValue.ObjectValue object -> tradeLogObject(object.fields());
        };
    }

    private static Map<String, Object> tradeLogObject(Map<String, TradeLogValue> values) {
        var result = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> result.put(key, tradeLogValue(value)));
        return result;
    }

    private static JsonNode requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return node;
    }

    private static String text(JsonNode node, String field) {
        if (!node.isString()) throw new IllegalArgumentException(field + " must be text");
        return node.stringValue();
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isNull()) return null;
        return text(node, field);
    }

    private static double decimal(JsonNode node, String field) {
        if (!node.isNumber()) throw new IllegalArgumentException(field + " must be numeric");
        return node.doubleValue();
    }

    private static int integer(JsonNode node, String field) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer");
        return node.intValue();
    }

    private static long longValue(JsonNode node, String field) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException(field + " must be an integer");
        return node.longValue();
    }

    private static Number number(double value) {
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) return (long) value;
        return value;
    }

    private static Number nullableNumber(Double value) {
        return value == null ? null : number(value);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
