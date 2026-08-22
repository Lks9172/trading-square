package io.macrosquare.company.domain.investment;

import java.util.List;

public record InvestmentDimension(
        String key,
        String label,
        int score,
        int confidence,
        DimensionState state,
        String summary,
        List<String> reasons,
        List<String> cautions
) {
    public InvestmentDimension {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        validateScore(score, "score");
        validateScore(confidence, "confidence");
        if (state == null) throw new IllegalArgumentException("state is required");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
    }

    public enum DimensionState {
        STRONG,
        POSITIVE,
        NEUTRAL,
        WEAK
    }

    private static void validateScore(int value, String field) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }
}
