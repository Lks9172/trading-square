package io.macrosquare.disclosure.application.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshDartServiceTest {

    @Test
    void choosesOnlyReportsConservativelyAvailableByTheStatutoryWindow() {
        assertEquals(new RefreshDartService.FinancialPeriod(2025, "11011"),
                RefreshDartService.latestAvailablePeriod(LocalDate.parse("2026-05-15")));
        assertEquals(new RefreshDartService.FinancialPeriod(2026, "11013"),
                RefreshDartService.latestAvailablePeriod(LocalDate.parse("2026-05-16")));
        assertEquals(new RefreshDartService.FinancialPeriod(2026, "11012"),
                RefreshDartService.latestAvailablePeriod(LocalDate.parse("2026-08-15")));
        assertEquals(new RefreshDartService.FinancialPeriod(2026, "11014"),
                RefreshDartService.latestAvailablePeriod(LocalDate.parse("2026-11-15")));
    }

    @Test
    void retainsOlderPeriodsAsOrderedFallbacksWhenOpenDartReturnsNoData() {
        var periods = RefreshDartService.availablePeriods(LocalDate.parse("2026-12-01"));

        assertEquals(4, periods.size());
        assertEquals("11014", periods.get(0).reportCode());
        assertEquals("11012", periods.get(1).reportCode());
        assertEquals("11013", periods.get(2).reportCode());
        assertEquals(new RefreshDartService.FinancialPeriod(2025, "11011"), periods.get(3));
    }
}
