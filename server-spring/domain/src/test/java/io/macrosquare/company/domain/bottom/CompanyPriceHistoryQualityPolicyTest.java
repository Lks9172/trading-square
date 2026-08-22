package io.macrosquare.company.domain.bottom;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyPriceHistoryQualityPolicyTest {

    private final CompanyPriceHistoryQualityPolicy policy = new CompanyPriceHistoryQualityPolicy();

    @Test
    void acceptsAContinuousSplitAdjustedSeries() {
        var assessment = policy.evaluate(List.of(
                point("2026-06-09", 101.0),
                point("2026-06-10", 99.0),
                point("2026-06-11", 102.0)
        ));

        assertTrue(assessment.eligible());
        assertTrue(assessment.warnings().isEmpty());
    }

    @Test
    void rejectsAnUnadjustedTenForOneSplitBeforeItCanBecomeAFakeBottom() {
        var assessment = policy.evaluate(List.of(
                point("2026-06-09", 1_010.0),
                point("2026-06-10", 990.0),
                point("2026-06-11", 102.0),
                point("2026-06-12", 104.0)
        ));

        assertFalse(assessment.eligible());
        assertTrue(assessment.warnings().stream().anyMatch(value -> value.contains("10x")));
    }

    @Test
    void rejectsDuplicateOrOutOfOrderDates() {
        var assessment = policy.evaluate(List.of(
                point("2026-06-10", 100.0),
                point("2026-06-10", 101.0)
        ));

        assertFalse(assessment.eligible());
        assertTrue(assessment.warnings().stream().anyMatch(value -> value.contains("out of order")));
    }

    @Test
    void rejectsMissingOrZeroVolumeBeforeItCanInflateRelativeVolume() {
        var missing = policy.evaluate(List.of(
                new BottomPatternPoint(LocalDate.parse("2026-06-10"), 100.0, null),
                point("2026-06-11", 101.0)
        ));
        var zero = policy.evaluate(List.of(
                new BottomPatternPoint(LocalDate.parse("2026-06-10"), 100.0, 0.0),
                point("2026-06-11", 101.0)
        ));

        assertFalse(missing.eligible());
        assertFalse(zero.eligible());
        assertTrue(missing.warnings().stream().anyMatch(value -> value.contains("volume")));
        assertTrue(zero.warnings().stream().anyMatch(value -> value.contains("volume")));
    }

    @Test
    void rejectsCloseOutsideTheReportedDailyRange() {
        var assessment = policy.evaluate(List.of(
                new BottomPatternPoint(LocalDate.parse("2026-06-10"), 105.0, 1_000_000.0, 104.0, 99.0),
                point("2026-06-11", 101.0)
        ));

        assertFalse(assessment.eligible());
        assertTrue(assessment.warnings().stream().anyMatch(value -> value.contains("daily high")));
    }

    @Test
    void rejectsANonPositiveCloseAtTheDomainBoundary() {
        assertThrows(IllegalArgumentException.class, () ->
                new BottomPatternPoint(LocalDate.parse("2026-06-10"), 0.0, 1_000_000.0));
    }

    private static BottomPatternPoint point(String date, double close) {
        return new BottomPatternPoint(LocalDate.parse(date), close, 1_000_000.0);
    }
}
