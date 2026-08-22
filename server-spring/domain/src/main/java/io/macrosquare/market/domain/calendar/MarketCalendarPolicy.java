package io.macrosquare.market.domain.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static io.macrosquare.market.domain.calendar.MarketCalendarEvent.Importance.HIGH;
import static io.macrosquare.market.domain.calendar.MarketCalendarEvent.Importance.MEDIUM;
import static io.macrosquare.market.domain.calendar.MarketCalendarEvent.Origin.PERSISTED_SOURCE;
import static io.macrosquare.market.domain.calendar.MarketCalendarEvent.Origin.SPRING_RULE;

/**
 * Normalizes persisted calendar rows and adds rule-based derivatives-expiry
 * reference windows.  It deliberately does not infer price direction from an
 * event date.
 */
public final class MarketCalendarPolicy {

    private static final int DEFAULT_HORIZON_DAYS = 370;
    private static final int EXPIRY_MONTHS_AHEAD = 4;

    public List<MarketCalendarEvent> evaluate(List<MarketCalendarEvent> persisted, LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        var lastDate = asOf.plusDays(DEFAULT_HORIZON_DAYS);
        var unique = new LinkedHashMap<String, MarketCalendarEvent>();

        for (var event : persisted == null ? List.<MarketCalendarEvent>of() : persisted) {
            if (event == null || event.origin() == SPRING_RULE) continue;
            if (event.date().isBefore(asOf) || event.date().isAfter(lastDate)) continue;
            put(unique, new MarketCalendarEvent(
                    event.date(), event.name(), event.category(), event.importance(), PERSISTED_SOURCE,
                    event.estimated()));
        }

        var firstMonth = YearMonth.from(asOf);
        for (var offset = 0; offset < EXPIRY_MONTHS_AHEAD; offset++) {
            var month = firstMonth.plusMonths(offset);
            var usExpiry = previousUsTradingDayIfNeeded(
                    month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY)));
            if (!usExpiry.isBefore(asOf)) {
                put(unique, new MarketCalendarEvent(
                        usExpiry,
                        "미국 월간 옵션 만기 변동성 구간",
                        "US_OPEX",
                        HIGH,
                        SPRING_RULE,
                        false));
            }

            var krxReference = month.atDay(1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.THURSDAY));
            if (!krxReference.isBefore(asOf)) {
                var quarterly = month.getMonthValue() % 3 == 0;
                put(unique, new MarketCalendarEvent(
                        krxReference,
                        quarterly
                                ? "코스피200 선물·옵션 동시 만기 예정 구간"
                                : "코스피200 옵션 만기 예정 구간",
                        "KRX_DERIVATIVES",
                        MEDIUM,
                        SPRING_RULE,
                        true));
            }
        }

        return unique.values().stream()
                .sorted(Comparator.comparing(MarketCalendarEvent::date)
                        .thenComparing(MarketCalendarEvent::name))
                .toList();
    }

    private static void put(LinkedHashMap<String, MarketCalendarEvent> target, MarketCalendarEvent event) {
        target.putIfAbsent(event.date() + "|" + event.category() + "|" + event.name(), event);
    }

    /**
     * Covers the exchange holidays that can collide with the third Friday.
     * The KRX reference remains estimated because Korean lunar/substitute
     * holidays require an official exchange calendar source.
     */
    private static LocalDate previousUsTradingDayIfNeeded(LocalDate date) {
        var candidate = date;
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY
                || isUsExchangeHoliday(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private static boolean isUsExchangeHoliday(LocalDate date) {
        if (date.equals(goodFriday(date.getYear()))) return true;
        if (date.equals(observed(LocalDate.of(date.getYear(), Month.JUNE, 19)))) return true;
        if (date.equals(observed(LocalDate.of(date.getYear(), Month.JULY, 4)))) return true;
        if (date.equals(observed(LocalDate.of(date.getYear(), Month.DECEMBER, 25)))) return true;
        return date.equals(observed(LocalDate.of(date.getYear(), Month.JANUARY, 1)));
    }

    private static LocalDate observed(LocalDate holiday) {
        return switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
    }

    private static LocalDate goodFriday(int year) {
        // Meeus/Jones/Butcher Gregorian Easter algorithm.
        var a = year % 19;
        var b = year / 100;
        var c = year % 100;
        var d = b / 4;
        var e = b % 4;
        var f = (b + 8) / 25;
        var g = (b - f + 1) / 3;
        var h = (19 * a + b - d - g + 15) % 30;
        var i = c / 4;
        var k = c % 4;
        var l = (32 + 2 * e + 2 * i - h - k) % 7;
        var m = (a + 11 * h + 22 * l) / 451;
        var month = (h + l - 7 * m + 114) / 31;
        var day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day).minusDays(2);
    }
}
