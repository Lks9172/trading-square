package io.macrosquare.market.domain.correlation;

import io.macrosquare.market.domain.indicator.MarketSeriesPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MarketCorrelationPolicy {

    public MarketCorrelationResult evaluate(
            int lookbackDays,
            List<String> requestedAssets,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var available = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        var missing = new ArrayList<String>();
        for (var asset : requestedAssets) {
            var points = histories.getOrDefault(asset, List.of());
            if (points.size() < 20) missing.add(asset);
            else available.put(asset, points);
        }
        var assets = List.copyOf(available.keySet());
        var matrix = new ArrayList<List<Double>>();
        for (var left = 0; left < assets.size(); left++) {
            var row = new ArrayList<Double>();
            for (var right = 0; right < assets.size(); right++) {
                if (left == right) row.add(1d);
                else row.add(correlation(
                        available.get(assets.get(left)), available.get(assets.get(right)), lookbackDays, asOf));
            }
            matrix.add(row);
        }
        return new MarketCorrelationResult(lookbackDays, assets, matrix, missing, asOf);
    }

    private static Double correlation(
            List<MarketSeriesPoint> left,
            List<MarketSeriesPoint> right,
            int lookbackDays,
            LocalDate asOf
    ) {
        var leftValues = new LinkedHashMap<LocalDate, Double>();
        left.forEach(point -> leftValues.put(point.date(), point.value()));
        var rightValues = new LinkedHashMap<LocalDate, Double>();
        right.forEach(point -> rightValues.put(point.date(), point.value()));
        var cutoff = asOf.minusDays(lookbackDays);
        var dates = rightValues.keySet().stream()
                .filter(leftValues::containsKey)
                .filter(date -> !date.isBefore(cutoff))
                .sorted()
                .toList();
        var x = new ArrayList<Double>();
        var y = new ArrayList<Double>();
        for (var index = 1; index < dates.size(); index++) {
            var previous = dates.get(index - 1);
            var current = dates.get(index);
            var lx = leftValues.get(previous);
            var ly = leftValues.get(current);
            var rx = rightValues.get(previous);
            var ry = rightValues.get(current);
            if (lx != null && ly != null && rx != null && ry != null && lx > 0 && rx > 0) {
                x.add(Math.log(ly / lx));
                y.add(Math.log(ry / rx));
            }
        }
        if (x.size() < 10) return null;
        var meanX = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var meanY = y.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (var index = 0; index < x.size(); index++) {
            var dx = x.get(index) - meanX;
            var dy = y.get(index) - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (varianceX == 0 || varianceY == 0) return null;
        return BigDecimal.valueOf(covariance / Math.sqrt(varianceX * varianceY))
                .setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
