package io.macrosquare.company.domain.bottom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class BottomPatternPolicy {

    public BottomPatternAnalysis analyze(List<BottomPatternPoint> history) {
        var series = history.stream()
                .filter(point -> Double.isFinite(point.close()) && point.close() > 0)
                .toList();

        if (series.size() < 40) {
            return new BottomPatternAnalysis(
                    null,
                    null,
                    null,
                    null,
                    series.isEmpty() ? null : series.getLast(),
                    BottomPatternPhase.DECLINE,
                    null,
                    null,
                    null
            );
        }

        var scanEnd = Math.max(20, series.size() - 15);
        var peakIndex = 0;
        for (var index = 1; index < scanEnd; index++) {
            if (series.get(index).close() > series.get(peakIndex).close()) {
                peakIndex = index;
            }
        }

        var candidateIndex = Math.min(series.size() - 1, peakIndex + 5);
        for (var index = Math.min(series.size() - 1, peakIndex + 5); index < series.size(); index++) {
            if (series.get(index).close() < series.get(candidateIndex).close()) {
                candidateIndex = index;
            }
        }

        var peakPoint = series.get(peakIndex);
        var candidatePoint = series.get(candidateIndex);
        var currentPoint = series.getLast();
        var declinePctFromPeak = percentChange(candidatePoint.close(), peakPoint.close());
        var reboundPctFromCandidate = percentChange(currentPoint.close(), candidatePoint.close());

        Integer swingHighIndex = null;
        for (var index = Math.min(series.size() - 1, candidateIndex + 3); index < series.size(); index++) {
            if (series.get(index).close() >= candidatePoint.close() * 1.08) {
                swingHighIndex = index;
                break;
            }
        }
        if (swingHighIndex != null) {
            for (var index = swingHighIndex; index < series.size(); index++) {
                if (series.get(index).close() > series.get(swingHighIndex).close()) {
                    swingHighIndex = index;
                }
            }
        }

        Integer retestIndex = null;
        if (swingHighIndex != null && swingHighIndex < series.size() - 3) {
            var localMinIndex = swingHighIndex + 1;
            for (var index = swingHighIndex + 1; index < series.size() - 1; index++) {
                if (series.get(index).close() < series.get(localMinIndex).close()) {
                    localMinIndex = index;
                }
            }
            var close = series.get(localMinIndex).close();
            var withinRetestBand = close >= candidatePoint.close() * 0.93
                    && close <= candidatePoint.close() * 1.12;
            if (withinRetestBand) {
                retestIndex = localMinIndex;
            }
        }

        Integer confirmIndex = null;
        var confirmBase = swingHighIndex != null
                ? series.get(swingHighIndex).close()
                : candidatePoint.close() * 1.15;
        var confirmStart = retestIndex != null ? retestIndex + 1 : candidateIndex + 1;
        for (var index = confirmStart; index < series.size(); index++) {
            var close = series.get(index).close();
            if (close >= confirmBase * 0.98 && close >= candidatePoint.close() * 1.12) {
                confirmIndex = index;
                break;
            }
        }

        var retestPoint = retestIndex == null ? null : series.get(retestIndex);
        var confirmPoint = confirmIndex == null ? null : series.get(confirmIndex);
        var retestGapPct = retestPoint == null
                ? null
                : percentChange(retestPoint.close(), candidatePoint.close());
        var phase = confirmPoint != null
                ? BottomPatternPhase.CONFIRM
                : retestPoint != null
                ? BottomPatternPhase.RETEST
                : reboundPctFromCandidate != null && reboundPctFromCandidate >= 6
                ? BottomPatternPhase.CANDIDATE
                : BottomPatternPhase.DECLINE;

        return new BottomPatternAnalysis(
                peakPoint,
                candidatePoint,
                retestPoint,
                confirmPoint,
                currentPoint,
                phase,
                declinePctFromPeak,
                reboundPctFromCandidate,
                retestGapPct
        );
    }

    private static Double percentChange(double current, double previous) {
        if (previous == 0) {
            return null;
        }
        var value = ((current - previous) / previous) * 100;
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
