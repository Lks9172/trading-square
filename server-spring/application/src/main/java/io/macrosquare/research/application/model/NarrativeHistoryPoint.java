package io.macrosquare.research.application.model;

public record NarrativeHistoryPoint(
        String date,
        int heatScore
) {
    public NarrativeHistoryPoint {
        if (date == null || date.isBlank()) throw new IllegalArgumentException("date is required");
        if (heatScore < 0 || heatScore > 100) throw new IllegalArgumentException("heatScore must be between 0 and 100");
    }
}
