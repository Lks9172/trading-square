package io.macrosquare.execution.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AssetTrancheSummary(
        String asset,
        List<Integer> executedStages,
        Integer nextStage,
        String latestRegime,
        Instant latestExecutedAt
) {
    public AssetTrancheSummary {
        asset = Objects.requireNonNull(asset, "asset");
        executedStages = List.copyOf(Objects.requireNonNull(executedStages, "executedStages"));
    }
}
