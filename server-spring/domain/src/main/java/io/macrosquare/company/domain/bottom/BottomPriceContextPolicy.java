package io.macrosquare.company.domain.bottom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Reconstructs the legacy 380-calendar-day company price context. */
public final class BottomPriceContextPolicy {

    private final BottomPatternPolicy patternPolicy;

    public BottomPriceContextPolicy(BottomPatternPolicy patternPolicy) {
        this.patternPolicy = Objects.requireNonNull(patternPolicy);
    }

    public BottomPriceContext evaluate(List<BottomPatternPoint> history) {
        Objects.requireNonNull(history, "history");
        var series = history.stream()
                .filter(point -> Double.isFinite(point.close()) && point.close() > 0)
                .toList();
        var pattern = patternPolicy.analyze(series);
        if (series.isEmpty()) return empty(pattern);

        var closes = series.stream().map(BottomPatternPoint::close).toList();
        var volumes = series.stream()
                .map(BottomPatternPoint::volume)
                .filter(Objects::nonNull)
                .filter(value -> Double.isFinite(value) && value > 0)
                .toList();
        var latestIndex = series.size() - 1;
        var latest = closes.getLast();
        var high = closes.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        var low = closes.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        var high120 = rollingHigh(series, latestIndex, 120);
        var ma20 = averageClose(series, latestIndex, 20);
        var ma50 = averageClose(series, latestIndex, 50);
        var recentVolume = mean(last(volumes, 20));
        var previousVolume = mean(previousWindow(volumes, 40, 20));

        var candidate = find(series, pattern.candidatePoint());
        var confirmation = find(series, pattern.confirmPoint());
        var retest = find(series, pattern.retestPoint());
        var candidateBaseVolume = averageVolumeBefore(series, date(pattern.candidatePoint()), 20);
        var confirmationBaseVolume = averageVolumeBefore(series, date(pattern.confirmPoint()), 20);
        var retestBaseVolume = averageVolumeBefore(series, date(pattern.retestPoint()), 20);
        var absorptionDate = pattern.retestPoint() != null
                ? pattern.retestPoint().date()
                : date(pattern.candidatePoint());
        var absorption = find(series, absorptionDate);
        var recent2dMaxVolume = maxVolumeBefore(series, absorptionDate, 2);
        var recent3dMaxVolume = maxVolumeBefore(series, absorptionDate, 3);
        var absorptionDrop = dailyCloseChange(series, absorptionDate);
        var priorDeclineDrop = dailyCloseChange(series, date(pattern.candidatePoint()));
        var contraction = absorptionDrop != null && priorDeclineDrop != null
                && absorptionDrop < 0 && priorDeclineDrop < 0
                ? round(Math.abs(absorptionDrop) / Math.abs(priorDeclineDrop), 2)
                : null;
        var absorptionIndex = indexOf(series, absorptionDate);

        return new BottomPriceContext(
                percentChange(latest, high),
                high120 == null ? null : percentChange(latest, high120),
                percentChange(latest, low),
                closes.size() > 30 ? percentChange(latest, closes.get(closes.size() - 31)) : null,
                recentVolume != null && previousVolume != null && previousVolume > 0
                        ? round(((recentVolume - previousVolume) / previousVolume) * 100, 1)
                        : null,
                ma20 == null ? null : percentChange(latest, ma20),
                ma20 != null && ma50 != null && ma20 < ma50,
                cumulativeCloseChange(series, absorptionDate, 3),
                series.subList(Math.max(0, series.size() - 260), series.size()),
                pattern,
                volumeRatio(candidate, candidateBaseVolume),
                volumeRatio(confirmation, confirmationBaseVolume),
                volumeRatio(retest, retestBaseVolume),
                volumeRatio(absorption, recent2dMaxVolume),
                volumeRatio(absorption, recent3dMaxVolume),
                absorptionDrop,
                priorDeclineDrop,
                contraction,
                absorptionDate,
                absorptionIndex < 0 ? null : latestIndex - absorptionIndex,
                absorption == null ? null : percentChange(latest, absorption.close())
        );
    }

    private static BottomPriceContext empty(BottomPatternAnalysis pattern) {
        return new BottomPriceContext(
                null, null, null, null, null, null, false, null, List.of(), pattern,
                null, null, null, null, null, null, null, null, null, null, null
        );
    }

    private static List<Double> last(List<Double> values, int count) {
        return values.subList(Math.max(0, values.size() - count), values.size());
    }

    /** Mirrors JavaScript {@code slice(-outer, -inner)}. */
    private static List<Double> previousWindow(List<Double> values, int outer, int inner) {
        var start = Math.max(0, values.size() - outer);
        var end = Math.max(0, values.size() - inner);
        return values.subList(Math.min(start, end), end);
    }

    private static Double mean(List<Double> values) {
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static Double averageClose(List<BottomPatternPoint> history, int endInclusive, int lookback) {
        var start = endInclusive - lookback + 1;
        if (start < 0) return null;
        return history.subList(start, endInclusive + 1).stream()
                .mapToDouble(BottomPatternPoint::close)
                .filter(value -> value > 0)
                .average()
                .orElse(Double.NaN);
    }

    private static Double rollingHigh(List<BottomPatternPoint> history, int endInclusive, int lookback) {
        var start = endInclusive - lookback + 1;
        if (start < 0) return null;
        return history.subList(start, endInclusive + 1).stream()
                .mapToDouble(BottomPatternPoint::close)
                .filter(value -> value > 0)
                .max()
                .orElse(Double.NaN);
    }

    private static Double averageVolumeBefore(
            List<BottomPatternPoint> history,
            LocalDate centerDate,
            int lookback
    ) {
        var index = indexOf(history, centerDate);
        if (index < 0) return null;
        return history.subList(Math.max(0, index - lookback), index).stream()
                .map(BottomPatternPoint::volume)
                .filter(Objects::nonNull)
                .filter(value -> Double.isFinite(value) && value > 0)
                .mapToDouble(Double::doubleValue)
                .average()
                .stream().boxed().findFirst().orElse(null);
    }

    private static Double maxVolumeBefore(
            List<BottomPatternPoint> history,
            LocalDate centerDate,
            int lookback
    ) {
        var index = indexOf(history, centerDate);
        if (index < 0) return null;
        return history.subList(Math.max(0, index - lookback), index).stream()
                .map(BottomPatternPoint::volume)
                .filter(Objects::nonNull)
                .filter(value -> Double.isFinite(value) && value > 0)
                .mapToDouble(Double::doubleValue)
                .max()
                .stream().boxed().findFirst().orElse(null);
    }

    private static Double dailyCloseChange(List<BottomPatternPoint> history, LocalDate date) {
        var index = indexOf(history, date);
        if (index <= 0) return null;
        return percentChange(history.get(index).close(), history.get(index - 1).close());
    }

    private static Double cumulativeCloseChange(
            List<BottomPatternPoint> history,
            LocalDate date,
            int days
    ) {
        var index = indexOf(history, date);
        if (index < days) return null;
        return percentChange(history.get(index).close(), history.get(index - days).close());
    }

    private static Double volumeRatio(BottomPatternPoint point, Double baseVolume) {
        return point == null || point.volume() == null || point.volume() <= 0
                || baseVolume == null || baseVolume <= 0
                ? null
                : round(point.volume() / baseVolume, 2);
    }

    private static BottomPatternPoint find(List<BottomPatternPoint> history, BottomPatternPoint point) {
        return point == null ? null : find(history, point.date());
    }

    private static BottomPatternPoint find(List<BottomPatternPoint> history, LocalDate date) {
        var index = indexOf(history, date);
        return index < 0 ? null : history.get(index);
    }

    private static int indexOf(List<BottomPatternPoint> history, LocalDate date) {
        if (date == null) return -1;
        for (var index = 0; index < history.size(); index++) {
            if (date.equals(history.get(index).date())) return index;
        }
        return -1;
    }

    private static LocalDate date(BottomPatternPoint point) {
        return point == null ? null : point.date();
    }

    private static Double percentChange(double current, double previous) {
        return previous == 0 ? null : round(((current - previous) / previous) * 100, 1);
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
