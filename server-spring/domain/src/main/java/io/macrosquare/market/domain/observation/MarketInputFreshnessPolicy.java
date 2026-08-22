package io.macrosquare.market.domain.observation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Decides whether a last-known observation may participate in a current decision.
 * Values remain available for audit/UI, but stale values must not silently satisfy
 * regime or asset-signal conditions.
 */
public final class MarketInputFreshnessPolicy {

    /**
     * FRED dates these flow-of-funds/fiscal observations at the beginning of the
     * represented quarter rather than at their later publication date. A 200-day
     * calendar-age gate can therefore expire a still-current Q1 observation before
     * the Q2 Z.1 release. 270 days covers the normal publication lag plus a short
     * grace period, while still failing closed when the next quarterly release is
     * genuinely missed.
     */
    private static final int RELEASE_LAGGED_QUARTERLY_MAX_AGE_DAYS = 270;

    private static final Set<String> QUARTERLY = Set.of(
            "TREASURY_MARKETABLE_ISSUANCE", "FEDERAL_DEBT_GDP", "FEDERAL_DEFICIT_GDP"
    );
    private static final Set<String> MONTHLY = Set.of(
            "UNRATE", "INDPRO", "CPI", "PCE"
    );
    /**
     * Money-stock observations carry a longer publication lag than most monthly
     * macro series. M2SL is monthly and WM2NS contains weekly observations, but
     * both are currently published in the same monthly H.6 release. Their period
     * date must therefore not expire before the next scheduled release.
     */
    private static final Set<String> MONTHLY_MONEY_STOCK_RELEASE = Set.of(
            "M2SL", "WM2NS"
    );
    private static final Set<String> WEEKLY = Set.of(
            "ICSA", "WALCL", "WRESBAL", "WDTGAL", "WTREGEN", "WRMFNS", "STLFSI4",
            "AAII_BULL_BEAR_SPREAD", "NAAIM_EXPOSURE"
    );

    public boolean usableRaw(String key, LocalDate observedOn, LocalDate asOf) {
        if (key == null || key.isBlank() || observedOn == null || asOf == null || observedOn.isAfter(asOf)) {
            return false;
        }
        return age(observedOn, asOf) <= maximumRawAgeDays(key);
    }

    public boolean usableDerived(String key, LocalDate calculatedOn, LocalDate asOf) {
        if (key == null || key.isBlank() || calculatedOn == null || asOf == null || calculatedOn.isAfter(asOf)) {
            return false;
        }
        return age(calculatedOn, asOf) <= maximumDerivedAgeDays(key);
    }

    public int maximumRawAgeDays(String key) {
        if (QUARTERLY.contains(key)) return RELEASE_LAGGED_QUARTERLY_MAX_AGE_DAYS;
        if (MONTHLY_MONEY_STOCK_RELEASE.contains(key)) return 95;
        if (MONTHLY.contains(key)) return 75;
        if (WEEKLY.contains(key)) return 14;
        return 7;
    }

    public int maximumDerivedAgeDays(String key) {
        if (key.startsWith("FISCAL_") || key.startsWith("BOND_VIGILANTE_")
                || key.contains("DEBT_GDP") || key.contains("DEFICIT_GDP")
                || key.contains("TREASURY_MARKETABLE_ISSUANCE")
                || key.startsWith("TREASURY_NET_ISSUANCE_")
                || "TREASURY_ISSUANCE_DIRECTION".equals(key)) {
            return RELEASE_LAGGED_QUARTERLY_MAX_AGE_DAYS;
        }
        if (key.startsWith("GLOBAL_M2") || key.startsWith("US_M2_")) return 95;
        if (key.startsWith("CPI_") || key.startsWith("PCE_")
                || key.startsWith("ISM_") || key.startsWith("STAGFLATION_")) {
            return 75;
        }
        // A derived projection retaining a weekly source key has the same
        // publication cadence as the underlying observation.
        if (WEEKLY.contains(key)) return 14;
        return 7;
    }

    private static long age(LocalDate observedOn, LocalDate asOf) {
        return ChronoUnit.DAYS.between(observedOn, asOf);
    }
}
