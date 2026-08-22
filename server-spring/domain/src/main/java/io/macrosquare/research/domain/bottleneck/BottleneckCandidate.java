package io.macrosquare.research.domain.bottleneck;

import java.util.List;
import java.util.Locale;

public record BottleneckCandidate(
        String ticker,
        String role,
        String theme,
        List<String> tags,
        BottleneckPriors priors
) {
    public BottleneckCandidate {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        if (theme == null || theme.isBlank()) throw new IllegalArgumentException("theme is required");
        ticker = ticker.trim().toUpperCase(Locale.ROOT);
        role = role.trim();
        theme = theme.trim();
        tags = List.copyOf(tags == null ? List.of() : tags);
        priors = priors == null ? new BottleneckPriors(null, null, null, null) : priors;
    }
}
