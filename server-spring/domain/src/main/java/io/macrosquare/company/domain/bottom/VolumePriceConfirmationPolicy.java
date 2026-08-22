package io.macrosquare.company.domain.bottom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Causal daily-price confirmation using a rolling 20-session VWAP proxy and
 * OBV-style signed volume pressure. Because Yahoo supplies daily bars, this is
 * explicitly not an intraday execution VWAP.
 */
public final class VolumePriceConfirmationPolicy {

    private static final int WINDOW = 20;

    public VolumePriceAnalysis evaluate(List<BottomPatternPoint> source) {
        if (source == null || source.size() < WINDOW) {
            return VolumePriceAnalysis.unavailable("OBV/VWAP 계산에 최소 20거래일이 필요합니다.");
        }
        var history = source.stream()
                .filter(point -> Double.isFinite(point.close()) && point.close() > 0)
                .sorted(Comparator.comparing(BottomPatternPoint::date))
                .toList();
        if (history.size() < WINDOW) {
            return VolumePriceAnalysis.unavailable("유효한 종가 기준 최소 20거래일이 필요합니다.");
        }
        var points = new ArrayList<VolumePricePoint>(history.size());
        for (var index = 0; index < history.size(); index++) {
            points.add(new VolumePricePoint(
                    history.get(index).date(),
                    rollingVwap(history, index),
                    signedVolumePressure(history, index)
            ));
        }

        var latest = history.getLast();
        var latestTechnical = points.getLast();
        var vwap20 = latestTechnical.vwap20();
        var pressure = latestTechnical.obvPressure20Pct();
        if (vwap20 == null || pressure == null) {
            return VolumePriceAnalysis.unavailable("유효한 거래량이 부족해 OBV/VWAP를 계산하지 못했습니다.");
        }
        var gap = ((latest.close() / vwap20) - 1) * 100;
        var priorVwap = points.size() > 5 ? points.get(points.size() - 6).vwap20() : null;
        var slope = priorVwap == null || priorVwap <= 0 ? null : ((vwap20 / priorVwap) - 1) * 100;

        var scores = new ArrayList<Integer>();
        scores.add(gapScore(gap));
        scores.add(pressureScore(pressure));
        if (slope != null) scores.add(slopeScore(slope));
        var score = (int) Math.round(scores.stream().mapToInt(Integer::intValue).average().orElse(0));

        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        if (gap >= 0) reasons.add("종가가 20일 일봉 VWAP proxy 위에 있습니다.");
        else cautions.add("종가가 20일 일봉 VWAP proxy 아래에 있습니다.");
        if (pressure >= 5) reasons.add("최근 20일 OBV 압력이 순매집 우위입니다.");
        else if (pressure <= -5) cautions.add("최근 20일 OBV 압력이 순분산 우위입니다.");
        if (slope != null && slope > 0) reasons.add("20일 VWAP proxy가 5거래일 전보다 상승했습니다.");
        else if (slope != null && slope < 0) cautions.add("20일 VWAP proxy 기울기가 아직 하락 중입니다.");

        var state = score >= 70 && gap >= 0 && pressure >= 0
                ? VolumePriceConfirmationState.ACCUMULATION
                : score < 45 || (gap < -3 && pressure < -5)
                ? VolumePriceConfirmationState.DISTRIBUTION
                : VolumePriceConfirmationState.NEUTRAL;
        return new VolumePriceAnalysis(
                Math.max(0, Math.min(100, score)),
                state,
                round(vwap20),
                round(gap),
                round(slope),
                round(pressure),
                reasons,
                cautions,
                points
        );
    }

    private static Double rollingVwap(List<BottomPatternPoint> history, int end) {
        var start = Math.max(0, end - WINDOW + 1);
        if (end - start + 1 < 5) return null;
        double weighted = 0;
        double totalVolume = 0;
        for (var index = start; index <= end; index++) {
            var point = history.get(index);
            var volume = point.volume() == null ? 0 : point.volume();
            if (volume <= 0) continue;
            var typical = point.high() != null && point.low() != null
                    ? (point.high() + point.low() + point.close()) / 3.0
                    : point.close();
            weighted += typical * volume;
            totalVolume += volume;
        }
        return totalVolume > 0 ? weighted / totalVolume : null;
    }

    private static Double signedVolumePressure(List<BottomPatternPoint> history, int end) {
        var start = Math.max(1, end - WINDOW + 1);
        if (end - start + 1 < 5) return null;
        double signed = 0;
        double total = 0;
        for (var index = start; index <= end; index++) {
            var volume = history.get(index).volume() == null ? 0 : history.get(index).volume();
            if (volume <= 0) continue;
            var change = Double.compare(history.get(index).close(), history.get(index - 1).close());
            signed += change * volume;
            total += volume;
        }
        return total > 0 ? (signed / total) * 100 : null;
    }

    private static int gapScore(double gap) {
        if (gap >= 3) return 84;
        if (gap >= 0) return 72;
        if (gap >= -3) return 50;
        return 25;
    }

    private static int pressureScore(double pressure) {
        if (pressure >= 15) return 88;
        if (pressure >= 5) return 74;
        if (pressure > -5) return 55;
        if (pressure > -15) return 35;
        return 18;
    }

    private static int slopeScore(double slope) {
        if (slope >= 1) return 82;
        if (slope >= 0) return 68;
        if (slope > -2) return 45;
        return 24;
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }
}
