package io.macrosquare.research.domain.bottleneck;

import java.util.List;

public record BottleneckCandidateScore(
        String ticker,
        String company,
        String role,
        String theme,
        int score,
        BottleneckConviction conviction,
        BottleneckComponentScores componentScores,
        List<BottleneckTextMatch> textMatches,
        List<String> reasons,
        BottleneckMetrics metrics
) {
    public BottleneckCandidateScore {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        textMatches = List.copyOf(textMatches == null ? List.of() : textMatches);
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }
}
