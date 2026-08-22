package io.macrosquare.company.domain.model;

import java.util.List;

public record ScoreBreakdown(int value, List<String> reasons) {

    public ScoreBreakdown {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
