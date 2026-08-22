package io.macrosquare.policy.domain.model;

public record PolicyEvidence(
        String phrase,
        PolicyDirection direction,
        int weight,
        String excerpt
) {
    public PolicyEvidence {
        if (phrase == null || phrase.isBlank()) throw new IllegalArgumentException("phrase is required");
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (weight < 1 || weight > 10) throw new IllegalArgumentException("weight must be between 1 and 10");
        excerpt = excerpt == null ? "" : excerpt;
    }
}
