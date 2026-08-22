package io.macrosquare.company.domain.bottom;

import java.util.List;
import java.util.Objects;

public record VolumePriceAnalysis(
        int score,
        VolumePriceConfirmationState state,
        Double vwap20,
        Double closeVsVwap20Pct,
        Double vwapSlope5dPct,
        Double obvPressure20Pct,
        List<String> reasons,
        List<String> cautions,
        List<VolumePricePoint> points
) {
    public VolumePriceAnalysis {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        Objects.requireNonNull(state, "state must not be null");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        cautions = List.copyOf(Objects.requireNonNull(cautions, "cautions"));
        points = List.copyOf(Objects.requireNonNull(points, "points"));
    }

    public static VolumePriceAnalysis unavailable(String reason) {
        return new VolumePriceAnalysis(
                0,
                VolumePriceConfirmationState.UNAVAILABLE,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(reason),
                List.of()
        );
    }
}
