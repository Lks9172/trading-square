package io.macrosquare.company.domain.model;

import java.util.List;
import java.util.Objects;

public record CompanyBuyScore(
        int appealScore,
        int crowdingScore,
        int buyScore,
        CompanyBuyLabel label,
        List<String> reasons
) {
    public CompanyBuyScore {
        validateScore(appealScore, "appealScore");
        validateScore(crowdingScore, "crowdingScore");
        validateScore(buyScore, "buyScore");
        Objects.requireNonNull(label, "label must not be null");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    private static void validateScore(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " must be between 0 and 100");
        }
    }
}
