package io.macrosquare.market.domain.observation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketInputFreshnessPolicyTest {

    private final MarketInputFreshnessPolicy policy = new MarketInputFreshnessPolicy();
    private final LocalDate asOf = LocalDate.parse("2026-08-05");

    @Test
    void excludesStaleDailyAndFutureValues() {
        assertTrue(policy.usableRaw("NASDAQ", asOf.minusDays(7), asOf));
        assertFalse(policy.usableRaw("NASDAQ", asOf.minusDays(8), asOf));
        assertFalse(policy.usableRaw("NASDAQ", asOf.plusDays(1), asOf));
    }

    @Test
    void respectsOfficialWeeklyMonthlyAndQuarterlyCadences() {
        assertTrue(policy.usableRaw("ICSA", asOf.minusDays(14), asOf));
        assertFalse(policy.usableRaw("ICSA", asOf.minusDays(15), asOf));
        assertTrue(policy.usableRaw("M2SL", asOf.minusDays(95), asOf));
        assertFalse(policy.usableRaw("M2SL", asOf.minusDays(96), asOf));
        assertTrue(policy.usableRaw("WDTGAL", asOf.minusDays(14), asOf));
        assertFalse(policy.usableRaw("WDTGAL", asOf.minusDays(15), asOf));
        assertTrue(policy.usableRaw("TREASURY_MARKETABLE_ISSUANCE", asOf.minusDays(270), asOf));
        assertFalse(policy.usableRaw("TREASURY_MARKETABLE_ISSUANCE", asOf.minusDays(271), asOf));
    }

    @Test
    void keepsTheLatestPublishedQuarterUsableUntilTheNextLaggedRelease() {
        var augustAfterQ1Z1Release = LocalDate.parse("2026-08-16");

        assertTrue(policy.usableRaw("TREASURY_MARKETABLE_ISSUANCE",
                LocalDate.parse("2026-01-01"), augustAfterQ1Z1Release));
        assertTrue(policy.usableRaw("FEDERAL_DEBT_GDP",
                LocalDate.parse("2026-01-01"), augustAfterQ1Z1Release));
    }

    @Test
    void doesNotLetOldDerivedValuesMasqueradeAsCurrent() {
        assertTrue(policy.usableDerived("NASDAQ_DISPARITY", asOf.minusDays(7), asOf));
        assertFalse(policy.usableDerived("NASDAQ_DISPARITY", asOf.minusDays(8), asOf));
        assertTrue(policy.usableDerived("GLOBAL_M2_PROXY", asOf.minusDays(95), asOf));
        assertTrue(policy.usableDerived("US_M2_3M_ANNUALIZED", asOf.minusDays(95), asOf));
        assertFalse(policy.usableDerived("US_M2_3M_ANNUALIZED", asOf.minusDays(96), asOf));
        assertTrue(policy.usableDerived("AAII_BULL_BEAR_SPREAD", asOf.minusDays(14), asOf));
        assertFalse(policy.usableDerived("AAII_BULL_BEAR_SPREAD", asOf.minusDays(15), asOf));
        assertTrue(policy.usableDerived("TREASURY_NET_ISSUANCE_CHANGE_BN", asOf.minusDays(270), asOf));
        assertFalse(policy.usableDerived("TREASURY_NET_ISSUANCE_CHANGE_BN", asOf.minusDays(271), asOf));
        assertTrue(policy.usableDerived("TREASURY_ISSUANCE_DIRECTION", asOf.minusDays(270), asOf));
    }
}
