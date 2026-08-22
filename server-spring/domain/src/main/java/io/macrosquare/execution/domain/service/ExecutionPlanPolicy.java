package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.AssetTrancheSummary;
import io.macrosquare.execution.domain.model.TrancheEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

public final class ExecutionPlanPolicy {

    private static final List<Double> DEFAULT_WEIGHTS = List.of(30d, 30d, 40d);

    public Double fallbackWeight(int stage) {
        return stage >= 1 && stage <= DEFAULT_WEIGHTS.size() ? DEFAULT_WEIGHTS.get(stage - 1) : null;
    }

    public List<AssetTrancheSummary> summarize(List<TrancheEntry> entries) {
        var grouped = new LinkedHashMap<String, List<TrancheEntry>>();
        for (var entry : entries) grouped.computeIfAbsent(entry.asset(), ignored -> new ArrayList<>()).add(entry);

        var result = new ArrayList<AssetTrancheSummary>();
        grouped.forEach((asset, values) -> {
            var sorted = values.stream().sorted(Comparator.comparing(TrancheEntry::executedAt)).toList();
            var stages = sorted.stream().map(TrancheEntry::stage).distinct().sorted().toList();
            var maximum = stages.isEmpty() ? 0 : stages.getLast();
            var latest = sorted.getLast();
            result.add(new AssetTrancheSummary(
                    asset,
                    stages,
                    maximum < 3 ? maximum + 1 : null,
                    latest.regimeAtEntry(),
                    latest.executedAt()
            ));
        });
        return List.copyOf(result);
    }

    public Boolean againstRecommendation(String userAction, String systemSignal) {
        if (userAction == null || systemSignal == null) return null;
        var action = userAction.toUpperCase();
        var userBuy = action.matches(".*(BUY|ADD|ENTER).*");
        var userSell = action.matches(".*(SELL|EXIT|REDUCE|TRIM).*");
        var systemBuy = systemSignal.equals("BUY") || systemSignal.equals("STRONG_BUY");
        var systemSell = systemSignal.equals("SELL") || systemSignal.equals("REDUCE");
        if ((userSell && systemBuy) || (userBuy && systemSell)) return true;
        if ((userBuy && systemBuy) || (userSell && systemSell)) return false;
        return null;
    }
}
