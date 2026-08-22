package io.macrosquare.execution.adapter.out.market;

import io.macrosquare.execution.application.model.MarketExecutionContext;
import io.macrosquare.execution.application.port.out.LoadMarketExecutionContextPort;
import io.macrosquare.market.application.model.MarketReadModels;
import io.macrosquare.market.application.port.in.QueryMarketReadUseCase;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SnapshotMarketExecutionContextAdapter implements LoadMarketExecutionContextPort {

    private final QueryMarketReadUseCase marketRead;

    public SnapshotMarketExecutionContextAdapter(QueryMarketReadUseCase marketRead) {
        this.marketRead = Objects.requireNonNull(marketRead);
    }

    @Override
    public Optional<MarketExecutionContext> loadCurrent() {
        var root = marketRead.latestSnapshot().root();
        var regime = text(object(root, "regime"), "regime");
        var prices = prices(object(root, "raw"));
        var weights = weights(optionalObject(root, "meta"));
        var signals = signals(optionalArray(root, "signals"));
        return Optional.of(new MarketExecutionContext(regime, prices, weights, signals));
    }

    private static Map<String, Double> prices(MarketReadModels.ObjectValue raw) {
        var result = new LinkedHashMap<String, Double>();
        raw.fields().forEach((asset, value) -> {
            if (!(value instanceof MarketReadModels.ObjectValue point)) return;
            var number = point.fields().get("value");
            if (number instanceof MarketReadModels.NumberValue numeric) {
                result.put(asset, numeric.value().doubleValue());
            }
        });
        return result;
    }

    private static Map<String, Map<Integer, Double>> weights(MarketReadModels.ObjectValue meta) {
        var result = new LinkedHashMap<String, Map<Integer, Double>>();
        var plans = meta.fields().get("executionPlans");
        if (!(plans instanceof MarketReadModels.ArrayValue array)) return result;
        for (var value : array.values()) {
            if (!(value instanceof MarketReadModels.ObjectValue plan)) continue;
            var asset = optionalText(plan, "asset");
            var stages = plan.fields().get("stages");
            if (asset == null || !(stages instanceof MarketReadModels.ArrayValue stageArray)) continue;
            var assetWeights = new LinkedHashMap<Integer, Double>();
            for (var stageValue : stageArray.values()) {
                if (!(stageValue instanceof MarketReadModels.ObjectValue stage)) continue;
                var stageNumber = optionalNumber(stage, "stage");
                var weight = optionalNumber(stage, "weightPct");
                if (stageNumber != null && weight != null) assetWeights.put(stageNumber.intValue(), weight.doubleValue());
            }
            result.put(asset, Map.copyOf(assetWeights));
        }
        return result;
    }

    private static Map<String, String> signals(MarketReadModels.ArrayValue signals) {
        var result = new LinkedHashMap<String, String>();
        for (var value : signals.values()) {
            if (!(value instanceof MarketReadModels.ObjectValue signal)) continue;
            var asset = optionalText(signal, "asset");
            var action = optionalText(signal, "signal");
            if (asset != null && action != null) result.put(asset, action);
        }
        return result;
    }

    private static MarketReadModels.ObjectValue object(MarketReadModels.ObjectValue root, String field) {
        var value = root.fields().get(field);
        if (value instanceof MarketReadModels.ObjectValue object) return object;
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static MarketReadModels.ObjectValue optionalObject(MarketReadModels.ObjectValue root, String field) {
        var value = root.fields().get(field);
        return value instanceof MarketReadModels.ObjectValue object ? object : new MarketReadModels.ObjectValue(Map.of());
    }

    private static MarketReadModels.ArrayValue optionalArray(MarketReadModels.ObjectValue root, String field) {
        var value = root.fields().get(field);
        return value instanceof MarketReadModels.ArrayValue array ? array : new MarketReadModels.ArrayValue(java.util.List.of());
    }

    private static String text(MarketReadModels.ObjectValue object, String field) {
        var value = optionalText(object, field);
        if (value == null) throw new IllegalArgumentException(field + " must be text");
        return value;
    }

    private static String optionalText(MarketReadModels.ObjectValue object, String field) {
        var value = object.fields().get(field);
        return value instanceof MarketReadModels.TextValue text ? text.value() : null;
    }

    private static Number optionalNumber(MarketReadModels.ObjectValue object, String field) {
        var value = object.fields().get(field);
        return value instanceof MarketReadModels.NumberValue number ? number.value() : null;
    }
}
