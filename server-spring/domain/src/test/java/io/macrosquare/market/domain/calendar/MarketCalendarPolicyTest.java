package io.macrosquare.market.domain.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static io.macrosquare.market.domain.calendar.MarketCalendarEvent.Importance.HIGH;
import static io.macrosquare.market.domain.calendar.MarketCalendarEvent.Origin.PERSISTED_SOURCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketCalendarPolicyTest {

    private final MarketCalendarPolicy policy = new MarketCalendarPolicy();

    @Test
    void removesPastRowsAndAddsUpcomingUsAndKrxExpiryWindows() {
        var result = policy.evaluate(List.of(
                new MarketCalendarEvent(LocalDate.parse("2026-07-29"), "old FOMC", "FOMC", HIGH,
                        PERSISTED_SOURCE, false),
                new MarketCalendarEvent(LocalDate.parse("2026-09-16"), "future FOMC", "FOMC", HIGH,
                        PERSISTED_SOURCE, false)
        ), LocalDate.parse("2026-08-06"));

        assertFalse(result.stream().anyMatch(event -> event.name().equals("old FOMC")));
        assertTrue(result.stream().anyMatch(event -> event.name().equals("future FOMC")));
        assertTrue(result.stream().anyMatch(event -> event.date().equals(LocalDate.parse("2026-08-21"))
                && event.category().equals("US_OPEX") && !event.estimated()));
        assertTrue(result.stream().anyMatch(event -> event.date().equals(LocalDate.parse("2026-08-13"))
                && event.category().equals("KRX_DERIVATIVES") && event.estimated()
                && event.name().equals("코스피200 옵션 만기 예정 구간")));
        assertTrue(result.stream().anyMatch(event -> event.date().equals(LocalDate.parse("2026-09-10"))
                && event.name().equals("코스피200 선물·옵션 동시 만기 예정 구간")));
    }

    @Test
    void movesThirdFridayExpiryToThursdayWhenJuneteenthClosesTheExchange() {
        var result = policy.evaluate(List.of(), LocalDate.parse("2026-06-01"));

        var june = result.stream().filter(event -> event.category().equals("US_OPEX")).findFirst().orElseThrow();
        assertEquals(LocalDate.parse("2026-06-18"), june.date());
    }
}
