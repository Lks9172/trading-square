package io.macrosquare.research.domain.bottleneck;

import java.util.List;

public record BottleneckTextMatch(
        String label,
        int count,
        double score,
        String reason,
        List<String> excerpts
) {
    public BottleneckTextMatch {
        excerpts = List.copyOf(excerpts == null ? List.of() : excerpts);
    }
}
