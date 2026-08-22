package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.Objects;

/** Official fund NAV, shares outstanding and net-assets point for one trading day. */
public record SectorFundHistoryPoint(
        LocalDate observedOn,
        double nav,
        double sharesOutstanding,
        double totalNetAssets
) {
    public SectorFundHistoryPoint {
        Objects.requireNonNull(observedOn, "observedOn");
        requirePositiveFinite(nav, "nav");
        requirePositiveFinite(sharesOutstanding, "sharesOutstanding");
        requirePositiveFinite(totalNetAssets, "totalNetAssets");
    }

    private static void requirePositiveFinite(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive and finite");
        }
    }
}
