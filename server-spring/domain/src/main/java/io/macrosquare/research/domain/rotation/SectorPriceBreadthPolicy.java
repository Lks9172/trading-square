package io.macrosquare.research.domain.rotation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Equal-count participation breadth across tracked sector constituents. */
public final class SectorPriceBreadthPolicy {

    public static final int MIN_COVERED_CONSTITUENTS = 10;
    public static final int MIN_COVERAGE_PCT = 70;
    public static final int MAX_PRICE_AGE_DAYS = 7;
    private static final int LONG_WINDOW = 200;

    public Optional<SectorPriceBreadthEvidence> evaluate(
            LocalDate asOfDate,
            List<SectorConstituentPriceSeries> rawSeries
    ) {
        if (asOfDate == null || rawSeries == null || rawSeries.isEmpty()) return Optional.empty();
        var covered = new ArrayList<SectorConstituentPriceSeries>();
        for (var series : rawSeries) {
            if (series == null || series.points().size() < LONG_WINDOW) continue;
            var latest = series.points().getLast();
            if (latest.observedOn().isAfter(asOfDate)
                    || latest.observedOn().isBefore(asOfDate.minusDays(MAX_PRICE_AGE_DAYS))) continue;
            covered.add(series);
        }
        var coveragePct = (int) Math.round(covered.size() * 100d / rawSeries.size());
        if (covered.size() < MIN_COVERED_CONSTITUENTS || coveragePct < MIN_COVERAGE_PCT) {
            return Optional.empty();
        }
        var oldest = covered.stream().map(value -> value.points().getLast().observedOn())
                .min(LocalDate::compareTo).orElseThrow();
        var latest = covered.stream().map(value -> value.points().getLast().observedOn())
                .max(LocalDate::compareTo).orElseThrow();
        var above20 = countAbove(covered, 20);
        var above50 = countAbove(covered, 50);
        var above200 = countAbove(covered, 200);
        var p20 = above20 * 100d / covered.size();
        var p50 = above50 * 100d / covered.size();
        var p200 = above200 * 100d / covered.size();
        var score = clamp((int) Math.round(p20 * 0.20 + p50 * 0.30 + p200 * 0.50));
        return Optional.of(new SectorPriceBreadthEvidence(
                asOfDate, oldest, latest, rawSeries.size(), covered.size(),
                above20, above50, above200, score));
    }

    private static int countAbove(List<SectorConstituentPriceSeries> series, int window) {
        return (int) series.stream().filter(value -> {
            var points = value.points();
            var current = points.getLast().close();
            var average = points.subList(points.size() - window, points.size()).stream()
                    .mapToDouble(SectorPricePoint::close).average().orElseThrow();
            return current >= average;
        }).count();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
