package io.macrosquare.execution.application.model;

import java.util.Map;

public record MarketExecutionContext(
        String regime,
        Map<String, Double> prices,
        Map<String, Map<Integer, Double>> trancheWeights,
        Map<String, String> signals
) {
    public MarketExecutionContext {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        trancheWeights = trancheWeights == null ? Map.of() : Map.copyOf(trancheWeights);
        signals = signals == null ? Map.of() : Map.copyOf(signals);
    }

    public Double price(String asset) {
        return prices.get(asset);
    }

    public Double trancheWeight(String asset, int stage) {
        return trancheWeights.getOrDefault(asset, Map.of()).get(stage);
    }

    public String signal(String asset) {
        return signals.get(asset);
    }
}
