package io.macrosquare.technical.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard close-only MACD(12,26,9) with confirmed-pivot regular divergence.
 * Pivot confirmation always waits for right-side observations to prevent
 * look-ahead leakage in live signals and historical validation.
 */
public final class MacdSignalPolicy {
    private static final int FAST = 12;
    private static final int SLOW = 26;
    private static final int SIGNAL = 9;
    private static final String METHOD = "EMA12-EMA26; signal=EMA9(MACD); histogram=MACD-signal; "
            + "regular divergence uses confirmed close pivots and is timing evidence, not a return probability";

    public MacdMultiTimeframeAnalysis evaluate(List<TechnicalClosePoint> input) {
        var dailyPoints = normalize(input);
        var weeklyPoints = weekly(dailyPoints);
        var daily = analyze(dailyPoints, 3, 20, 5, 60);
        var weekly = analyze(weeklyPoints, 2, 8, 3, 26);
        var provisional = !dailyPoints.isEmpty()
                && dailyPoints.getLast().date().getDayOfWeek().getValue() < DayOfWeek.FRIDAY.getValue();
        return new MacdMultiTimeframeAnalysis(daily, weekly, provisional);
    }

    private static MacdSignalAnalysis analyze(
            List<TechnicalClosePoint> points,
            int pivotRadius,
            int activeAge,
            int minimumPivotGap,
            int maximumPivotGap
    ) {
        var asOf = points.isEmpty() ? LocalDate.MIN : points.getLast().date();
        if (points.size() < SLOW + SIGNAL) return MacdSignalAnalysis.unavailable(asOf, points.size());

        var closes = points.stream().mapToDouble(TechnicalClosePoint::close).toArray();
        var fast = ema(closes, FAST);
        var slow = ema(closes, SLOW);
        var macd = new double[closes.length];
        java.util.Arrays.fill(macd, Double.NaN);
        for (var index = 0; index < closes.length; index++) {
            if (Double.isFinite(fast[index]) && Double.isFinite(slow[index])) {
                macd[index] = fast[index] - slow[index];
            }
        }
        var signal = emaFromFirstFinite(macd, SIGNAL);
        var histogram = new double[closes.length];
        java.util.Arrays.fill(histogram, Double.NaN);
        for (var index = 0; index < closes.length; index++) {
            if (Double.isFinite(macd[index]) && Double.isFinite(signal[index])) {
                histogram[index] = macd[index] - signal[index];
            }
        }

        var latest = points.size() - 1;
        if (!Double.isFinite(histogram[latest])) return MacdSignalAnalysis.unavailable(asOf, points.size());
        var epsilon = Math.max(1e-9, Math.abs(signal[latest]) * 1e-6);
        var position = histogram[latest] > epsilon
                ? MacdSignalAnalysis.SignalPosition.ABOVE_SIGNAL
                : histogram[latest] < -epsilon
                ? MacdSignalAnalysis.SignalPosition.BELOW_SIGNAL
                : MacdSignalAnalysis.SignalPosition.AT_SIGNAL;
        var zero = macd[latest] > epsilon
                ? MacdSignalAnalysis.ZeroRegime.ABOVE_ZERO
                : macd[latest] < -epsilon
                ? MacdSignalAnalysis.ZeroRegime.BELOW_ZERO
                : MacdSignalAnalysis.ZeroRegime.AT_ZERO;

        var cross = latestCross(points, histogram);
        var histogramState = histogramState(histogram, latest, epsilon);
        var divergence = latestDivergence(
                points, histogram, pivotRadius, activeAge, minimumPivotGap, maximumPivotGap);

        return new MacdSignalAnalysis(
                asOf,
                macd[latest], signal[latest], histogram[latest], position, zero,
                cross.type(), cross.date(), cross.age(), histogramState,
                divergence.type(), divergence.startDate(), divergence.endDate(), divergence.confirmedDate(),
                divergence.age(), divergence.active(), points.size(), METHOD
        );
    }

    private static CrossEvent latestCross(List<TechnicalClosePoint> points, double[] histogram) {
        for (var index = histogram.length - 1; index > 0; index--) {
            if (!Double.isFinite(histogram[index]) || !Double.isFinite(histogram[index - 1])) continue;
            if (histogram[index] > 0 && histogram[index - 1] <= 0) {
                return new CrossEvent(MacdSignalAnalysis.CrossType.BULLISH_CROSS,
                        points.get(index).date(), points.size() - 1 - index);
            }
            if (histogram[index] < 0 && histogram[index - 1] >= 0) {
                return new CrossEvent(MacdSignalAnalysis.CrossType.BEARISH_CROSS,
                        points.get(index).date(), points.size() - 1 - index);
            }
        }
        return new CrossEvent(MacdSignalAnalysis.CrossType.NONE, null, null);
    }

    private static MacdSignalAnalysis.HistogramState histogramState(double[] values, int latest, double epsilon) {
        if (latest < 1 || !Double.isFinite(values[latest - 1])) return MacdSignalAnalysis.HistogramState.UNAVAILABLE;
        var current = values[latest];
        var previous = values[latest - 1];
        if (Math.abs(current - previous) <= epsilon) return MacdSignalAnalysis.HistogramState.FLAT;
        if (current >= 0) {
            return current > previous
                    ? MacdSignalAnalysis.HistogramState.EXPANDING_POSITIVE
                    : MacdSignalAnalysis.HistogramState.CONTRACTING_POSITIVE;
        }
        return current < previous
                ? MacdSignalAnalysis.HistogramState.EXPANDING_NEGATIVE
                : MacdSignalAnalysis.HistogramState.CONTRACTING_NEGATIVE;
    }

    private static DivergenceEvent latestDivergence(
            List<TechnicalClosePoint> points,
            double[] histogram,
            int radius,
            int activeAge,
            int minimumGap,
            int maximumGap
    ) {
        var lows = pivots(points, histogram, radius, false);
        var highs = pivots(points, histogram, radius, true);
        DivergenceEvent result = DivergenceEvent.none();
        for (var candidates : List.of(lows, highs)) {
            for (var index = 1; index < candidates.size(); index++) {
                var prior = candidates.get(index - 1);
                var current = candidates.get(index);
                var gap = current.index() - prior.index();
                if (gap < minimumGap || gap > maximumGap) continue;
                var type = divergenceType(prior, current);
                if (type == MacdSignalAnalysis.DivergenceType.NONE) continue;
                var confirmedIndex = current.index() + radius;
                var age = points.size() - 1 - confirmedIndex;
                var candidate = new DivergenceEvent(
                        type, prior.date(), current.date(), points.get(confirmedIndex).date(), age, age <= activeAge);
                if (result.confirmedDate() == null || candidate.confirmedDate().isAfter(result.confirmedDate())) {
                    result = candidate;
                }
            }
        }
        return result;
    }

    private static MacdSignalAnalysis.DivergenceType divergenceType(Pivot prior, Pivot current) {
        if (prior.high() != current.high()) return MacdSignalAnalysis.DivergenceType.NONE;
        if (!current.high()
                && current.price() <= prior.price() * .995
                && prior.histogram() < 0 && current.histogram() < 0
                && current.histogram() >= prior.histogram() + Math.abs(prior.histogram()) * .10) {
            return MacdSignalAnalysis.DivergenceType.BULLISH;
        }
        if (current.high()
                && current.price() >= prior.price() * 1.005
                && prior.histogram() > 0 && current.histogram() > 0
                && current.histogram() <= prior.histogram() - Math.abs(prior.histogram()) * .10) {
            return MacdSignalAnalysis.DivergenceType.BEARISH;
        }
        return MacdSignalAnalysis.DivergenceType.NONE;
    }

    private static List<Pivot> pivots(
            List<TechnicalClosePoint> points, double[] histogram, int radius, boolean high) {
        var values = new ArrayList<Pivot>();
        var first = Math.max(radius, SLOW + SIGNAL - 2);
        for (var index = first; index < points.size() - radius; index++) {
            if (!Double.isFinite(histogram[index])) continue;
            var price = points.get(index).close();
            var pivot = true;
            for (var offset = 1; offset <= radius && pivot; offset++) {
                if (high) {
                    pivot = price > points.get(index - offset).close()
                            && price >= points.get(index + offset).close();
                } else {
                    pivot = price < points.get(index - offset).close()
                            && price <= points.get(index + offset).close();
                }
            }
            if (pivot) values.add(new Pivot(index, points.get(index).date(), price, histogram[index], high));
        }
        return values;
    }

    private static double[] ema(double[] values, int period) {
        var result = new double[values.length];
        java.util.Arrays.fill(result, Double.NaN);
        if (values.length < period) return result;
        var sum = 0d;
        for (var index = 0; index < period; index++) sum += values[index];
        result[period - 1] = sum / period;
        var alpha = 2d / (period + 1d);
        for (var index = period; index < values.length; index++) {
            result[index] = values[index] * alpha + result[index - 1] * (1 - alpha);
        }
        return result;
    }

    private static double[] emaFromFirstFinite(double[] values, int period) {
        var result = new double[values.length];
        java.util.Arrays.fill(result, Double.NaN);
        var first = -1;
        for (var index = 0; index < values.length; index++) {
            if (Double.isFinite(values[index])) { first = index; break; }
        }
        if (first < 0 || values.length - first < period) return result;
        var seedIndex = first + period - 1;
        var sum = 0d;
        for (var index = first; index <= seedIndex; index++) sum += values[index];
        result[seedIndex] = sum / period;
        var alpha = 2d / (period + 1d);
        for (var index = seedIndex + 1; index < values.length; index++) {
            result[index] = values[index] * alpha + result[index - 1] * (1 - alpha);
        }
        return result;
    }

    private static List<TechnicalClosePoint> normalize(List<TechnicalClosePoint> input) {
        if (input == null || input.isEmpty()) return List.of();
        var byDate = new java.util.TreeMap<LocalDate, TechnicalClosePoint>();
        input.forEach(point -> byDate.put(point.date(), point));
        return List.copyOf(byDate.values());
    }

    private static List<TechnicalClosePoint> weekly(List<TechnicalClosePoint> daily) {
        Map<LocalDate, TechnicalClosePoint> byWeek = new LinkedHashMap<>();
        daily.stream().sorted(Comparator.comparing(TechnicalClosePoint::date)).forEach(point -> {
            var week = point.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeek.put(week, point);
        });
        return List.copyOf(byWeek.values());
    }

    private record CrossEvent(MacdSignalAnalysis.CrossType type, LocalDate date, Integer age) {}
    private record Pivot(int index, LocalDate date, double price, double histogram, boolean high) {}
    private record DivergenceEvent(
            MacdSignalAnalysis.DivergenceType type,
            LocalDate startDate,
            LocalDate endDate,
            LocalDate confirmedDate,
            Integer age,
            boolean active
    ) {
        private static DivergenceEvent none() {
            return new DivergenceEvent(MacdSignalAnalysis.DivergenceType.NONE, null, null, null, null, false);
        }
    }
}
