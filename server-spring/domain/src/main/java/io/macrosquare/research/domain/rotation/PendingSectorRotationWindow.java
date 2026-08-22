package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record PendingSectorRotationWindow(
        UUID runId,
        LocalDate asOfDate,
        LocalDate priceAnchorOn,
        int tradingSessions
) {
    public PendingSectorRotationWindow {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(priceAnchorOn, "priceAnchorOn");
        if (priceAnchorOn.isAfter(asOfDate)) throw new IllegalArgumentException("price anchor is after signal date");
        if (tradingSessions != 21 && tradingSessions != 63 && tradingSessions != 126) {
            throw new IllegalArgumentException("unsupported outcome horizon");
        }
    }
}
