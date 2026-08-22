package io.macrosquare.company.domain.horizon;

import java.util.List;
import java.util.Objects;

public record CompanyHorizonSignal(
        CompanyHorizon horizon,
        int score,
        CompanyHorizonAction action,
        int confidence,
        CompanyHorizonWeights weights,
        String summary,
        List<String> reasons
) {
    public CompanyHorizonSignal {
        Objects.requireNonNull(horizon, "horizon");
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        Objects.requireNonNull(action, "action");
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence must be between 0 and 100");
        }
        Objects.requireNonNull(weights, "weights");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
