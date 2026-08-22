package io.macrosquare.company.domain.bottom;

import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.BearishReversalStage;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.MovingAverageState;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.PriceLocation;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.PriceStructurePoint;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.PriceZone;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.RecoveryStage;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis.TrendState;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis.FibonacciLevel;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis.SwingDirection;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis.TimeframeReliability;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis.ZoneState;

import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interprets daily OHLCV as support/resistance zones, Dow swing structure,
 * regression channel, moving-average state, and multi-factor RSI confluence.
 */
public final class PriceStructurePolicy {

    public static final String METHODOLOGY =
            "지지·저항은 단일 가격이 아닌 피벗 군집 구간으로 계산합니다. "
                    + "다우 고점·저점 구조, 252일 로그 회귀 채널, 20·50·100·200일선, "
                    + "14일 RSI와 거래량을 함께 보며 RSI 과매도만으로는 매수 점수를 주지 않습니다. "
                    + "점수는 심리·구조 합치도이며 수익 확률이 아닙니다.";
    public static final String FIBONACCI_METHODOLOGY =
            "최근 504거래일의 교대 피벗 중 일중 변동성 대비 충분히 큰 최신 파동을 선택합니다. "
                    + "상승 파동은 저점→고점, 하락 파동은 고점→저점으로 0.236·0.382·0.5·0.618·0.786을 계산합니다. "
                    + "같은 원시 시세를 주봉으로 별도 집계한 레벨과 독립 피벗 지지·저항/회귀 채널이 겹칠 때만 합치도를 높이며, "
                    + "피보나치 단독으로 매수·수익 확률을 만들지 않습니다.";

    private static final int MINIMUM_POINTS = 60;
    private static final int CHART_POINTS = 260;
    private static final int SWING_WINDOW = 5;

    public PriceStructureAnalysis evaluate(List<BottomPatternPoint> rawPoints) {
        var points = normalize(rawPoints);
        if (points.size() < MINIMUM_POINTS) {
            return PriceStructureAnalysis.unavailable(chartPoints(points, null));
        }

        var closes = points.stream().mapToDouble(BottomPatternPoint::close).toArray();
        var latest = points.getLast();
        var current = latest.close();
        var sma20 = average(closes, 20);
        var sma50 = average(closes, 50);
        var sma100 = average(closes, 100);
        var sma200 = average(closes, 200);
        var convergence = convergence(sma20, sma50, sma100, sma200);
        var maState = movingAverageState(current, sma20, sma50, sma100, sma200, convergence);
        var rsi14 = rsi(closes, 14);
        var channel = channel(points, Math.min(252, points.size()));
        var swings = swings(points);
        var trend = trend(swings, points);
        var bearishStage = bearishStage(swings, points, rsi14);
        var zones = zones(points, swings);
        var support = nearestSupport(zones, current);
        var resistance = nearestResistance(zones, current);
        var fibonacci = fibonacci(points, swings, support, resistance, channel);
        var volumeBreakout = volumeBreakout(points, channel);
        var stopHuntReclaim = stopHuntReclaim(points, support);
        var location = location(current, channel, support, resistance, volumeBreakout);
        var recovery = recovery(swings, points, trend, location, stopHuntReclaim);
        var consolidation = consolidation(points, volumeBreakout);
        var accumulationPressure = accumulationPressure(points, 20);
        var oversoldConfluence = rsi14 != null
                && rsi14 <= 35
                && (location == PriceLocation.SUPPORT_ZONE
                || location == PriceLocation.LOWER_CHANNEL
                || stopHuntReclaim)
                && accumulationPressure != null
                && accumulationPressure >= -0.08
                && bearishStage != BearishReversalStage.PRIOR_LOW_BROKEN;

        var reasons = reasons(
                trend, recovery, location, maState, rsi14, oversoldConfluence,
                volumeBreakout, stopHuntReclaim, support, resistance, consolidation);
        var cautions = cautions(
                trend, bearishStage, location, maState, rsi14, oversoldConfluence,
                support, resistance, accumulationPressure);
        var score = score(
                trend, bearishStage, recovery, location, maState,
                volumeBreakout, stopHuntReclaim, oversoldConfluence);

        return new PriceStructureAnalysis(
                score,
                trend,
                bearishStage,
                recovery,
                location,
                maState,
                round1(rsi14),
                round2(sma20),
                round2(sma50),
                round2(sma100),
                round2(sma200),
                round2(convergence),
                round2(channel == null ? null : channel.lower()),
                round2(channel == null ? null : channel.mid()),
                round2(channel == null ? null : channel.upper()),
                round1(channel == null ? null : channel.positionPct()),
                round1(channel == null ? null : channel.annualizedSlopePct()),
                support,
                resistance,
                consolidation.days(),
                round1(consolidation.rangePct()),
                volumeBreakout,
                stopHuntReclaim,
                oversoldConfluence,
                fibonacci,
                reasons,
                cautions,
                METHODOLOGY,
                chartPoints(points, channel)
        );
    }

    private static List<BottomPatternPoint> normalize(List<BottomPatternPoint> rawPoints) {
        if (rawPoints == null || rawPoints.isEmpty()) return List.of();
        var byDate = new LinkedHashMap<java.time.LocalDate, BottomPatternPoint>();
        rawPoints.stream()
                .filter(value -> value != null && value.close() > 0 && Double.isFinite(value.close()))
                .sorted(Comparator.comparing(BottomPatternPoint::date))
                .forEach(value -> byDate.put(value.date(), value));
        return List.copyOf(byDate.values());
    }

    private static List<PriceStructurePoint> chartPoints(
            List<BottomPatternPoint> points,
            Channel latestChannel
    ) {
        if (points.isEmpty()) return List.of();
        var start = Math.max(0, points.size() - CHART_POINTS);
        var result = new ArrayList<PriceStructurePoint>(points.size() - start);
        var closes = points.stream().mapToDouble(BottomPatternPoint::close).toArray();
        var channelStart = latestChannel == null ? Integer.MAX_VALUE : points.size() - latestChannel.length();
        for (var index = start; index < points.size(); index++) {
            Channel pointChannel = null;
            if (latestChannel != null && index >= channelStart) {
                pointChannel = latestChannel.at(index - channelStart);
            }
            result.add(new PriceStructurePoint(
                    points.get(index).date(),
                    points.get(index).close(),
                    round2(averageAt(closes, index, 20)),
                    round2(averageAt(closes, index, 50)),
                    round2(averageAt(closes, index, 100)),
                    round2(averageAt(closes, index, 200)),
                    round2(pointChannel == null ? null : pointChannel.lower()),
                    round2(pointChannel == null ? null : pointChannel.mid()),
                    round2(pointChannel == null ? null : pointChannel.upper())
            ));
        }
        return List.copyOf(result);
    }

    private static FibonacciRetracementAnalysis fibonacci(
            List<BottomPatternPoint> points,
            List<Swing> rawSwings,
            PriceZone support,
            PriceZone resistance,
            Channel channel
    ) {
        var selected = selectMajorSwing(points, rawSwings);
        if (selected == null) return FibonacciRetracementAnalysis.unavailable();

        var direction = selected.start().kind() == SwingKind.LOW
                ? SwingDirection.UP_SWING : SwingDirection.DOWN_SWING;
        var low = Math.min(selected.start().price(), selected.end().price());
        var high = Math.max(selected.start().price(), selected.end().price());
        var range = high - low;
        if (range <= 0) return FibonacciRetracementAnalysis.unavailable();

        var levels = fibonacciLevels(direction, low, high);
        var current = points.getLast().close();
        var currentRatio = direction == SwingDirection.UP_SWING
                ? (high - current) / range
                : (current - low) / range;
        var nearest = levels.stream()
                .min(Comparator.comparingDouble(value -> Math.abs(value.price() - current)))
                .orElseThrow();
        var nearestGap = (current / nearest.price() - 1) * 100;

        var weekly = weeklyPoints(points);
        var weeklySelection = weekly.size() < 12
                ? null : selectMajorSwing(weekly, swings(weekly, 2));
        var weeklyLevels = weeklySelection == null
                ? List.<FibonacciLevel>of()
                : fibonacciLevels(
                        weeklySelection.start().kind() == SwingKind.LOW
                                ? SwingDirection.UP_SWING : SwingDirection.DOWN_SWING,
                        Math.min(weeklySelection.start().price(), weeklySelection.end().price()),
                        Math.max(weeklySelection.start().price(), weeklySelection.end().price())
                );
        var weeklyConfluence = weeklyLevels.stream()
                .anyMatch(value -> Math.abs(value.price() / nearest.price() - 1) <= 0.025);
        var zone = direction == SwingDirection.UP_SWING ? support : resistance;
        var supportResistanceConfluence = zone != null
                && nearest.price() >= zone.lower() * 0.985
                && nearest.price() <= zone.upper() * 1.015;
        var channelConfluence = channel != null && java.util.stream.Stream.of(
                        channel.lower(), channel.mid(), channel.upper())
                .anyMatch(value -> Math.abs(value / nearest.price() - 1) <= 0.02);
        var confluenceScore = Math.min(
                100,
                (weeklyConfluence ? 35 : 0)
                        + (supportResistanceConfluence ? 40 : 0)
                        + (channelConfluence ? 25 : 0)
        );
        var zoneState = fibonacciZone(currentRatio);
        var timeframe = weeklyConfluence
                ? TimeframeReliability.WEEKLY_CONFIRMED
                : TimeframeReliability.DAILY_ONLY;
        var cautions = fibonacciCautions(direction, zoneState, weeklyConfluence, supportResistanceConfluence);
        var summary = fibonacciSummary(direction, nearest, nearestGap, zoneState, confluenceScore);

        return new FibonacciRetracementAnalysis(
                direction,
                selected.start().date(),
                selected.end().date(),
                round2(selected.start().price()),
                round2(selected.end().price()),
                round1(selected.amplitudePct()),
                round2(current),
                round3(currentRatio),
                levels,
                nearest.ratio(),
                nearest.price(),
                round1(nearestGap),
                timeframe,
                weeklyConfluence,
                supportResistanceConfluence,
                channelConfluence,
                confluenceScore,
                zoneState,
                summary,
                cautions,
                FIBONACCI_METHODOLOGY
        );
    }

    private static MajorSwing selectMajorSwing(
            List<BottomPatternPoint> points,
            List<Swing> rawSwings
    ) {
        if (points.size() < 12 || rawSwings.size() < 2) return null;
        var firstIndex = Math.max(0, points.size() - 504);
        var alternating = alternatingSwings(rawSwings).stream()
                .filter(value -> value.index() >= firstIndex)
                .toList();
        if (alternating.size() < 2) return null;
        var threshold = majorSwingThreshold(points.subList(firstIndex, points.size()));
        MajorSwing fallback = null;
        for (var index = 1; index < alternating.size(); index++) {
            var start = alternating.get(index - 1);
            var end = alternating.get(index);
            if (start.kind() == end.kind() || end.index() - start.index() < 4) continue;
            var amplitude = Math.abs(end.price() / start.price() - 1) * 100;
            var candidate = new MajorSwing(start, end, amplitude);
            if (fallback == null || candidate.amplitudePct() > fallback.amplitudePct()) fallback = candidate;
            if (amplitude >= threshold) {
                // Iteration is chronological, so the last qualifying pair is the
                // latest clear major wave rather than the largest old wave.
                fallback = candidate;
            }
        }
        if (fallback == null) return null;
        return fallback.amplitudePct() >= Math.max(5, threshold * 0.65) ? fallback : null;
    }

    private static List<Swing> alternatingSwings(List<Swing> values) {
        var result = new ArrayList<Swing>();
        for (var value : values) {
            if (result.isEmpty()) {
                result.add(value);
                continue;
            }
            var prior = result.getLast();
            if (value.index() == prior.index()) {
                continue;
            }
            if (value.kind() != prior.kind()) {
                result.add(value);
                continue;
            }
            var moreExtreme = value.kind() == SwingKind.HIGH
                    ? value.price() > prior.price()
                    : value.price() < prior.price();
            if (moreExtreme) result.set(result.size() - 1, value);
        }
        return List.copyOf(result);
    }

    private static double majorSwingThreshold(List<BottomPatternPoint> points) {
        var ranges = points.stream()
                .mapToDouble(value -> Math.max(0, (high(value) / low(value) - 1) * 100))
                .filter(value -> Double.isFinite(value) && value > 0)
                .sorted()
                .toArray();
        var median = ranges.length == 0 ? 1.5 : ranges[ranges.length / 2];
        return Math.max(8, Math.min(18, median * 6));
    }

    private static List<FibonacciLevel> fibonacciLevels(
            SwingDirection direction,
            double low,
            double high
    ) {
        var range = high - low;
        return List.of(0.236, 0.382, 0.5, 0.618, 0.786).stream()
                .map(ratio -> new FibonacciLevel(
                        ratio,
                        round2(direction == SwingDirection.UP_SWING
                                ? high - range * ratio
                                : low + range * ratio),
                        fibonacciLabel(ratio)
                ))
                .toList();
    }

    private static String fibonacciLabel(double ratio) {
        if (ratio == 0.236) return "0.236 · 얕은 되돌림 후보";
        if (ratio == 0.382) return "0.382 · 1차 되돌림 후보";
        if (ratio == 0.5) return "0.5 · 중간값 관찰 구간";
        if (ratio == 0.618) return "0.618 · 깊은 되돌림 후보";
        return "0.786 · 추세 훼손 경계 후보";
    }

    private static ZoneState fibonacciZone(double ratio) {
        if (ratio < 0) return ZoneState.EXTENSION;
        if (ratio < 0.441) return ZoneState.SHALLOW_RETRACEMENT;
        if (ratio < 0.559) return ZoneState.MODERATE_RETRACEMENT;
        if (ratio < 0.702) return ZoneState.DEEP_RETRACEMENT;
        if (ratio <= 0.806) return ZoneState.LAST_DEFENSE;
        return ZoneState.LAST_DEFENSE_BROKEN;
    }

    private static String fibonacciSummary(
            SwingDirection direction,
            FibonacciLevel nearest,
            double gapPct,
            ZoneState zoneState,
            int confluenceScore
    ) {
        var directionLabel = direction == SwingDirection.UP_SWING ? "상승 파동의 눌림" : "하락 파동의 반등";
        var stateLabel = switch (zoneState) {
            case EXTENSION -> "기존 파동을 넘어선 확장 구간";
            case SHALLOW_RETRACEMENT -> "얕은 조정 구간";
            case MODERATE_RETRACEMENT -> "중간 조정 구간";
            case DEEP_RETRACEMENT -> "깊은 조정 구간";
            case LAST_DEFENSE -> "0.786 추세 훼손 경계 구간";
            case LAST_DEFENSE_BROKEN -> "0.786 기준선 이탈";
            case UNAVAILABLE -> "계산 불가";
        };
        return "%s · 가장 가까운 %s 가격 %.2f(현재 대비 %+.1f%%) · %s · 합치도 %d/100(확률 아님)"
                .formatted(directionLabel, nearest.label(), nearest.price(), gapPct, stateLabel, confluenceScore);
    }

    private static List<String> fibonacciCautions(
            SwingDirection direction,
            ZoneState zoneState,
            boolean weeklyConfluence,
            boolean supportResistanceConfluence
    ) {
        var values = new ArrayList<String>();
        if (direction == SwingDirection.DOWN_SWING) {
            values.add("하락 파동의 피보나치는 반등 저항 후보이며 분할매수 지지선으로 해석하지 않습니다.");
        }
        if (zoneState == ZoneState.LAST_DEFENSE) {
            values.add("0.786은 통계적으로 보장된 방어선이 아닌 추세 훼손 경계 후보입니다. 반등 실패 시 구조를 다시 확인합니다.");
        } else if (zoneState == ZoneState.LAST_DEFENSE_BROKEN) {
            values.add("선택 파동의 0.786 기준을 이탈해 평균단가를 낮추는 추가 매수보다 구조 회복 확인이 우선입니다.");
        }
        if (!weeklyConfluence) values.add("주봉에서 같은 가격대가 재확인되지 않아 일봉 레벨 신뢰도만 부여합니다.");
        if (!supportResistanceConfluence) {
            values.add("독립 계산한 지지·저항 구간과 겹치지 않아 피보나치 단독 근거로 사용할 수 없습니다.");
        }
        return values.stream().distinct().limit(4).toList();
    }

    private static List<BottomPatternPoint> weeklyPoints(List<BottomPatternPoint> points) {
        var fields = WeekFields.ISO;
        var groups = new LinkedHashMap<String, WeeklyPoint>();
        for (var point : points) {
            var key = point.date().get(fields.weekBasedYear()) + "-"
                    + point.date().get(fields.weekOfWeekBasedYear());
            groups.computeIfAbsent(key, ignored -> new WeeklyPoint()).add(point);
        }
        return groups.values().stream().map(WeeklyPoint::freeze).toList();
    }

    private static List<Swing> swings(List<BottomPatternPoint> points) {
        return swings(points, SWING_WINDOW);
    }

    private static List<Swing> swings(List<BottomPatternPoint> points, int window) {
        var result = new ArrayList<Swing>();
        for (var index = window; index < points.size() - window; index++) {
            var high = high(points.get(index));
            var low = low(points.get(index));
            var pivotHigh = true;
            var pivotLow = true;
            for (var offset = -window; offset <= window; offset++) {
                if (offset == 0) continue;
                if (high(points.get(index + offset)) >= high) pivotHigh = false;
                if (low(points.get(index + offset)) <= low) pivotLow = false;
            }
            if (pivotHigh) result.add(new Swing(index, points.get(index).date(), high, SwingKind.HIGH));
            if (pivotLow) result.add(new Swing(index, points.get(index).date(), low, SwingKind.LOW));
        }
        result.sort(Comparator.comparingInt(Swing::index));
        return List.copyOf(result);
    }

    private static TrendState trend(List<Swing> swings, List<BottomPatternPoint> points) {
        var highs = ofKind(swings, SwingKind.HIGH);
        var lows = ofKind(swings, SwingKind.LOW);
        if (highs.size() < 2 || lows.size() < 2) {
            var return60 = returnPct(points, 60);
            if (return60 != null && return60 >= 8) return TrendState.UPTREND;
            if (return60 != null && return60 <= -8) return TrendState.DOWNTREND;
            return TrendState.RANGE;
        }
        var highRising = highs.getLast().price() > highs.get(highs.size() - 2).price() * 1.005;
        var highFalling = highs.getLast().price() < highs.get(highs.size() - 2).price() * 0.995;
        var lowRising = lows.getLast().price() > lows.get(lows.size() - 2).price() * 1.005;
        var lowFalling = lows.getLast().price() < lows.get(lows.size() - 2).price() * 0.995;
        if (highRising && lowRising) return TrendState.UPTREND;
        if (highFalling && lowFalling) return TrendState.DOWNTREND;
        var return60 = returnPct(points, 60);
        var range60 = rangePct(points, 60);
        if (return60 != null && range60 != null && Math.abs(return60) <= 5 && range60 <= 16) {
            return TrendState.RANGE;
        }
        return TrendState.TRANSITION;
    }

    private static BearishReversalStage bearishStage(
            List<Swing> swings,
            List<BottomPatternPoint> points,
            Double currentRsi
    ) {
        var highs = ofKind(swings, SwingKind.HIGH);
        var lows = ofKind(swings, SwingKind.LOW);
        if (lows.isEmpty()) return BearishReversalStage.INTACT;
        var current = points.getLast().close();
        var priorLow = lows.getLast().price();
        if (current < priorLow * 0.99) return BearishReversalStage.PRIOR_LOW_BROKEN;
        if (highs.size() >= 2
                && highs.getLast().price() < highs.get(highs.size() - 2).price() * 0.99) {
            return BearishReversalStage.STRUCTURAL_CRACK;
        }
        var recentReturn = returnPct(points, 20);
        var priorReturn = returnPctEndingAt(points, Math.max(0, points.size() - 21), 20);
        var slowing = recentReturn != null && priorReturn != null
                && recentReturn < priorReturn - 6
                && recentReturn < 3;
        var rsiWeak = currentRsi != null && currentRsi < 48
                && current < maxClose(points, 60) * 0.98;
        return slowing || rsiWeak
                ? BearishReversalStage.MOMENTUM_WEAKENING
                : BearishReversalStage.INTACT;
    }

    private static RecoveryStage recovery(
            List<Swing> swings,
            List<BottomPatternPoint> points,
            TrendState trend,
            PriceLocation location,
            boolean stopHuntReclaim
    ) {
        var highs = ofKind(swings, SwingKind.HIGH);
        var lows = ofKind(swings, SwingKind.LOW);
        var current = points.getLast().close();
        if (highs.size() >= 2 && lows.size() >= 2) {
            var higherLow = lows.getLast().price() > lows.get(lows.size() - 2).price() * 1.005;
            var priorHighBroken = current > highs.getLast().price() * 1.01;
            if (higherLow && priorHighBroken) return RecoveryStage.RETEST_HELD;
            if (priorHighBroken) return RecoveryStage.STRUCTURE_BREAK;
        } else if (!highs.isEmpty() && current > highs.getLast().price() * 1.01) {
            return RecoveryStage.STRUCTURE_BREAK;
        }
        var rebound = reboundFromLow(points, 60);
        if (rebound != null && rebound >= 8) return RecoveryStage.REBOUND;
        if (stopHuntReclaim
                || location == PriceLocation.SUPPORT_ZONE
                || location == PriceLocation.LOWER_CHANNEL
                || (trend == TrendState.RANGE && rebound != null && rebound >= 2)) {
            return RecoveryStage.BASE_BUILDING;
        }
        return RecoveryStage.NONE;
    }

    private static List<ZoneCandidate> zones(List<BottomPatternPoint> points, List<Swing> allSwings) {
        var firstIndex = Math.max(0, points.size() - 504);
        var tolerance = zoneTolerance(points.subList(firstIndex, points.size()));
        var candidates = new ArrayList<MutableZone>();
        for (var swing : allSwings) {
            if (swing.index() < firstIndex) continue;
            MutableZone match = null;
            var bestGap = Double.MAX_VALUE;
            for (var candidate : candidates) {
                var gap = Math.abs(swing.price() / candidate.center() - 1);
                if (gap <= tolerance && gap < bestGap) {
                    match = candidate;
                    bestGap = gap;
                }
            }
            if (match == null) {
                match = new MutableZone(swing.price());
                candidates.add(match);
            }
            match.add(swing);
        }
        var lastIndex = points.size() - 1;
        return candidates.stream()
                .filter(value -> value.touches >= 2)
                .map(value -> value.freeze(tolerance, lastIndex))
                .sorted(Comparator.comparingDouble(ZoneCandidate::midpoint))
                .toList();
    }

    private static PriceZone nearestSupport(List<ZoneCandidate> zones, double current) {
        return zones.stream()
                .filter(value -> value.midpoint() <= current * 1.025)
                .min(Comparator.comparingDouble(value -> Math.abs(current - value.midpoint())))
                .map(ZoneCandidate::zone)
                .orElse(null);
    }

    private static PriceZone nearestResistance(List<ZoneCandidate> zones, double current) {
        return zones.stream()
                .filter(value -> value.midpoint() >= current * 0.975)
                .min(Comparator.comparingDouble(value -> Math.abs(current - value.midpoint())))
                .map(ZoneCandidate::zone)
                .orElse(null);
    }

    private static PriceLocation location(
            double current,
            Channel channel,
            PriceZone support,
            PriceZone resistance,
            boolean volumeBreakout
    ) {
        if (support != null && current < support.lower() * 0.985) return PriceLocation.BREAKDOWN;
        if (channel != null && current < channel.lower() * 0.985) return PriceLocation.BREAKDOWN;
        if (volumeBreakout && (channel == null || current >= channel.upper() * 0.995)) {
            return PriceLocation.BREAKOUT;
        }
        if (support != null && current >= support.lower() * 0.985 && current <= support.upper() * 1.015) {
            return PriceLocation.SUPPORT_ZONE;
        }
        if (resistance != null
                && current >= resistance.lower() * 0.985
                && current <= resistance.upper() * 1.015) {
            return PriceLocation.RESISTANCE_ZONE;
        }
        if (channel == null) return PriceLocation.MID_CHANNEL;
        if (channel.positionPct() <= 22) return PriceLocation.LOWER_CHANNEL;
        if (channel.positionPct() >= 82) return PriceLocation.UPPER_CHANNEL;
        return PriceLocation.MID_CHANNEL;
    }

    private static MovingAverageState movingAverageState(
            double current,
            Double sma20,
            Double sma50,
            Double sma100,
            Double sma200,
            Double convergence
    ) {
        if (sma20 == null || sma50 == null) return MovingAverageState.UNAVAILABLE;
        if (convergence != null && convergence <= 4.0) return MovingAverageState.CONVERGED;
        if (sma100 != null && sma200 != null
                && current > sma20 && sma20 > sma50 && sma50 > sma100 && sma100 > sma200) {
            return MovingAverageState.BULLISH_ALIGNED;
        }
        if (sma100 != null && sma200 != null
                && current < sma20 && sma20 < sma50 && sma50 < sma100 && sma100 < sma200) {
            return MovingAverageState.BEARISH_ALIGNED;
        }
        return MovingAverageState.TRANSITION;
    }

    private static boolean volumeBreakout(List<BottomPatternPoint> points, Channel channel) {
        if (points.size() < 21) return false;
        var latest = points.getLast();
        var latestVolume = latest.volume();
        var averageVolume = averageVolume(points, points.size() - 21, points.size() - 1);
        if (latestVolume == null || averageVolume == null || averageVolume <= 0) return false;
        var priorHigh = points.subList(Math.max(0, points.size() - 61), points.size() - 1)
                .stream().mapToDouble(PriceStructurePolicy::high).max().orElse(latest.close());
        var priceBreakout = latest.close() > priorHigh * 1.005
                || (channel != null && latest.close() > channel.upper() * 1.005);
        return priceBreakout && latestVolume / averageVolume >= 1.4;
    }

    private static boolean stopHuntReclaim(List<BottomPatternPoint> points, PriceZone support) {
        if (support == null || points.size() < 10) return false;
        var start = Math.max(1, points.size() - 10);
        for (var index = start; index < points.size(); index++) {
            var point = points.get(index);
            if (low(point) >= support.lower() * 0.995 || point.close() <= support.lower()) continue;
            var average = averageVolume(points, Math.max(0, index - 20), index);
            var ratio = point.volume() != null && average != null && average > 0
                    ? point.volume() / average : 0;
            if (ratio >= 1.2 && points.getLast().close() >= support.lower()) return true;
        }
        return false;
    }

    private static Consolidation consolidation(
            List<BottomPatternPoint> points,
            boolean latestIsVolumeBreakout
    ) {
        var end = latestIsVolumeBreakout ? points.size() - 1 : points.size();
        if (end < 20) return new Consolidation(0, null);
        for (var days = Math.min(120, end); days >= 20; days--) {
            var slice = points.subList(end - days, end);
            var min = slice.stream().mapToDouble(PriceStructurePolicy::low).min().orElse(0);
            var max = slice.stream().mapToDouble(PriceStructurePolicy::high).max().orElse(0);
            if (min <= 0) continue;
            var range = (max / min - 1) * 100;
            var allowed = days >= 90 ? 20 : days >= 60 ? 16 : days >= 40 ? 13 : 10;
            var net = Math.abs((slice.getLast().close() / slice.getFirst().close() - 1) * 100);
            if (range <= allowed && net <= allowed * 0.55) return new Consolidation(days, range);
        }
        return new Consolidation(0, null);
    }

    private static Channel channel(List<BottomPatternPoint> points, int length) {
        if (length < MINIMUM_POINTS) return null;
        var start = points.size() - length;
        var meanX = (length - 1) / 2.0;
        var meanY = 0.0;
        for (var index = 0; index < length; index++) {
            meanY += Math.log(points.get(start + index).close());
        }
        meanY /= length;
        var numerator = 0.0;
        var denominator = 0.0;
        for (var index = 0; index < length; index++) {
            var x = index - meanX;
            numerator += x * (Math.log(points.get(start + index).close()) - meanY);
            denominator += x * x;
        }
        if (denominator == 0) return null;
        var slope = numerator / denominator;
        var intercept = meanY - slope * meanX;
        var residualSquares = 0.0;
        for (var index = 0; index < length; index++) {
            var expected = intercept + slope * index;
            var residual = Math.log(points.get(start + index).close()) - expected;
            residualSquares += residual * residual;
        }
        var deviation = Math.sqrt(residualSquares / Math.max(1, length - 2));
        return Channel.of(length, intercept, slope, deviation, points.getLast().close());
    }

    private static Double accumulationPressure(List<BottomPatternPoint> points, int days) {
        if (points.size() < 2) return null;
        var start = Math.max(1, points.size() - days);
        var signed = 0.0;
        var total = 0.0;
        for (var index = start; index < points.size(); index++) {
            var volume = points.get(index).volume();
            if (volume == null || volume <= 0) continue;
            total += volume;
            signed += Math.signum(points.get(index).close() - points.get(index - 1).close()) * volume;
        }
        return total == 0 ? null : signed / total;
    }

    private static List<String> reasons(
            TrendState trend,
            RecoveryStage recovery,
            PriceLocation location,
            MovingAverageState maState,
            Double rsi,
            boolean oversoldConfluence,
            boolean volumeBreakout,
            boolean stopHuntReclaim,
            PriceZone support,
            PriceZone resistance,
            Consolidation consolidation
    ) {
        var result = new ArrayList<String>();
        if (trend == TrendState.UPTREND) result.add("고점과 저점이 함께 높아지는 상승 구조입니다.");
        if (recovery == RecoveryStage.STRUCTURE_BREAK) {
            result.add("직전 스윙 고점을 돌파해 하락 구조의 1차 전환을 확인했습니다.");
        } else if (recovery == RecoveryStage.RETEST_HELD) {
            result.add("고점 돌파 뒤 높아진 저점을 지켜 구조 전환의 재확인까지 끝냈습니다.");
        } else if (recovery == RecoveryStage.REBOUND) {
            result.add("최근 저점에서 의미 있는 반등이 진행 중입니다.");
        }
        if (location == PriceLocation.SUPPORT_ZONE && support != null) {
            result.add("반복 확인된 지지 구간 " + priceRange(support) + " 안에 있습니다.");
        } else if (location == PriceLocation.LOWER_CHANNEL) {
            result.add("252일 가격 채널 하단부로 추격보다 분할 접근에 유리한 위치입니다.");
        }
        if (support != null && support.roleFlip()) {
            result.add("과거 저항이 지지로 바뀐 역할 전환 구간이 확인됩니다.");
        }
        if (maState == MovingAverageState.BULLISH_ALIGNED) {
            result.add("20·50·100·200일선이 상승 정배열입니다.");
        } else if (maState == MovingAverageState.CONVERGED) {
            result.add("주요 이동평균선이 수렴해 다음 방향 선택을 앞둔 구간입니다.");
        }
        if (oversoldConfluence) {
            result.add("RSI 과매도와 지지/채널·거래량 조건이 함께 충족됐습니다(RSI 단독 신호 아님).");
        }
        if (stopHuntReclaim) result.add("지지 이탈 뒤 거래량을 동반해 구간을 회복한 스톱헌트 재진입이 보입니다.");
        if (volumeBreakout) result.add("가격 돌파에 최근 평균 대비 1.4배 이상 거래량이 동반됐습니다.");
        if (consolidation.days() >= 40) {
            result.add(consolidation.days() + "거래일 횡보로 시간 조정과 에너지 압축이 누적됐습니다.");
        }
        if (result.isEmpty() && resistance != null) {
            result.add("다음 저항 구간은 " + priceRange(resistance) + "입니다.");
        }
        return result.stream().distinct().limit(6).toList();
    }

    private static List<String> cautions(
            TrendState trend,
            BearishReversalStage stage,
            PriceLocation location,
            MovingAverageState maState,
            Double rsi,
            boolean oversoldConfluence,
            PriceZone support,
            PriceZone resistance,
            Double accumulationPressure
    ) {
        var result = new ArrayList<String>();
        switch (stage) {
            case MOMENTUM_WEAKENING -> result.add("1단계: 상승 모멘텀이 약해져 신규 추격보다 경계가 우선입니다.");
            case STRUCTURAL_CRACK -> result.add("2단계: 낮아진 고점이 확인돼 비중 확대보다 축소/확인을 우선해야 합니다.");
            case PRIOR_LOW_BROKEN -> result.add("3단계: 직전 저점이 깨져 기존 상승 가설의 청산 조건이 작동했습니다.");
            default -> {
            }
        }
        if (trend == TrendState.DOWNTREND) result.add("고점과 저점이 함께 낮아지는 하락 구조입니다.");
        if (location == PriceLocation.UPPER_CHANNEL) {
            result.add("가격 채널 상단부라 좋은 기업이어도 신규 추격 매수 효율이 낮습니다.");
        } else if (location == PriceLocation.RESISTANCE_ZONE && resistance != null) {
            result.add("반복 저항 구간 " + priceRange(resistance) + "에 가까워 돌파 확인이 필요합니다.");
        } else if (location == PriceLocation.BREAKDOWN) {
            result.add("지지/채널 하단을 이탈해 회복 전까지 바닥으로 단정하면 안 됩니다.");
        }
        if (maState == MovingAverageState.BEARISH_ALIGNED) {
            result.add("20·50·100·200일선이 하락 역배열입니다.");
        }
        if (rsi != null && rsi <= 30 && !oversoldConfluence) {
            result.add("RSI는 과매도지만 지지·구조 전환·수급 합치가 없어 단독 매수 신호가 아닙니다.");
        }
        if (support == null) result.add("반복 확인된 지지 구간을 충분히 식별하지 못했습니다.");
        if (accumulationPressure != null && accumulationPressure < -0.15) {
            result.add("최근 거래량은 매집보다 분산 압력이 우세합니다.");
        }
        return result.stream().distinct().limit(6).toList();
    }

    private static int score(
            TrendState trend,
            BearishReversalStage stage,
            RecoveryStage recovery,
            PriceLocation location,
            MovingAverageState maState,
            boolean volumeBreakout,
            boolean stopHuntReclaim,
            boolean oversoldConfluence
    ) {
        var score = 50;
        score += switch (trend) {
            case UPTREND -> 12;
            case RANGE -> 2;
            case TRANSITION, UNAVAILABLE -> 0;
            case DOWNTREND -> -10;
        };
        score += switch (stage) {
            case INTACT -> 4;
            case MOMENTUM_WEAKENING -> -8;
            case STRUCTURAL_CRACK -> -18;
            case PRIOR_LOW_BROKEN -> -35;
            case UNAVAILABLE -> 0;
        };
        score += switch (recovery) {
            case NONE, UNAVAILABLE -> 0;
            case BASE_BUILDING -> 5;
            case REBOUND -> 9;
            case STRUCTURE_BREAK -> 14;
            case RETEST_HELD -> 18;
        };
        score += switch (location) {
            case BREAKOUT -> 10;
            case SUPPORT_ZONE, LOWER_CHANNEL -> 11;
            case MID_CHANNEL, UNAVAILABLE -> 0;
            case RESISTANCE_ZONE, UPPER_CHANNEL -> -8;
            case BREAKDOWN -> -22;
        };
        score += switch (maState) {
            case BULLISH_ALIGNED -> 10;
            case CONVERGED -> 3;
            case TRANSITION, UNAVAILABLE -> 0;
            case BEARISH_ALIGNED -> -8;
        };
        if (volumeBreakout) score += 8;
        if (stopHuntReclaim) score += 10;
        if (oversoldConfluence) score += 8;
        return Math.max(0, Math.min(100, score));
    }

    private static List<Swing> ofKind(List<Swing> swings, SwingKind kind) {
        return swings.stream().filter(value -> value.kind() == kind).toList();
    }

    private static double zoneTolerance(List<BottomPatternPoint> points) {
        var ranges = points.stream()
                .filter(value -> high(value) > low(value))
                .mapToDouble(value -> (high(value) / low(value) - 1))
                .sorted()
                .toArray();
        var median = ranges.length == 0 ? 0.02 : ranges[ranges.length / 2];
        return Math.max(0.015, Math.min(0.045, median * 1.25));
    }

    private static Double average(double[] values, int days) {
        return values.length < days ? null : averageAt(values, values.length - 1, days);
    }

    private static Double averageAt(double[] values, int endInclusive, int days) {
        if (endInclusive < days - 1) return null;
        var sum = 0.0;
        for (var index = endInclusive - days + 1; index <= endInclusive; index++) sum += values[index];
        return sum / days;
    }

    private static Double averageVolume(List<BottomPatternPoint> points, int fromInclusive, int toExclusive) {
        var sum = 0.0;
        var count = 0;
        for (var index = Math.max(0, fromInclusive); index < Math.min(points.size(), toExclusive); index++) {
            var volume = points.get(index).volume();
            if (volume == null || volume <= 0) continue;
            sum += volume;
            count++;
        }
        return count == 0 ? null : sum / count;
    }

    private static Double convergence(Double... values) {
        var finite = java.util.Arrays.stream(values).filter(value -> value != null).toList();
        if (finite.size() < 3) return null;
        var min = finite.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        var max = finite.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        var mean = finite.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return mean == 0 ? null : (max - min) / mean * 100;
    }

    private static Double rsi(double[] closes, int period) {
        if (closes.length <= period) return null;
        var gains = 0.0;
        var losses = 0.0;
        var start = closes.length - period;
        for (var index = start; index < closes.length; index++) {
            var delta = closes[index] - closes[index - 1];
            if (delta > 0) gains += delta;
            else losses -= delta;
        }
        if (losses == 0) return 100.0;
        if (gains == 0) return 0.0;
        var relativeStrength = (gains / period) / (losses / period);
        return 100 - 100 / (1 + relativeStrength);
    }

    private static Double returnPct(List<BottomPatternPoint> points, int days) {
        return returnPctEndingAt(points, points.size() - 1, days);
    }

    private static Double returnPctEndingAt(List<BottomPatternPoint> points, int endInclusive, int days) {
        var start = endInclusive - days;
        if (start < 0 || endInclusive >= points.size()) return null;
        return (points.get(endInclusive).close() / points.get(start).close() - 1) * 100;
    }

    private static Double rangePct(List<BottomPatternPoint> points, int days) {
        if (points.size() < days) return null;
        var slice = points.subList(points.size() - days, points.size());
        var min = slice.stream().mapToDouble(PriceStructurePolicy::low).min().orElse(0);
        var max = slice.stream().mapToDouble(PriceStructurePolicy::high).max().orElse(0);
        return min <= 0 ? null : (max / min - 1) * 100;
    }

    private static Double reboundFromLow(List<BottomPatternPoint> points, int days) {
        var slice = points.subList(Math.max(0, points.size() - days), points.size());
        var min = slice.stream().mapToDouble(PriceStructurePolicy::low).min().orElse(0);
        return min <= 0 ? null : (points.getLast().close() / min - 1) * 100;
    }

    private static double maxClose(List<BottomPatternPoint> points, int days) {
        return points.subList(Math.max(0, points.size() - days), points.size())
                .stream().mapToDouble(BottomPatternPoint::close).max().orElse(points.getLast().close());
    }

    private static double high(BottomPatternPoint point) {
        return point.high() == null ? point.close() : Math.max(point.high(), point.close());
    }

    private static double low(BottomPatternPoint point) {
        return point.low() == null ? point.close() : Math.min(point.low(), point.close());
    }

    private static String priceRange(PriceZone zone) {
        return String.format(java.util.Locale.ROOT, "%.2f~%.2f", zone.lower(), zone.upper());
    }

    private static Double round1(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    private static Double round2(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private static Double round3(Double value) {
        return value == null ? null : Math.round(value * 1000.0) / 1000.0;
    }

    private enum SwingKind {
        HIGH,
        LOW
    }

    private record Swing(int index, java.time.LocalDate date, double price, SwingKind kind) {
    }

    private record MajorSwing(Swing start, Swing end, double amplitudePct) {
    }

    private record Consolidation(int days, Double rangePct) {
    }

    private record ZoneCandidate(double midpoint, PriceZone zone) {
    }

    private static final class MutableZone {
        private double weightedPrice;
        private int touches;
        private int highTouches;
        private int lowTouches;
        private int latestIndex;

        private MutableZone(double initialPrice) {
            weightedPrice = initialPrice;
        }

        private double center() {
            return touches == 0 ? weightedPrice : weightedPrice / touches;
        }

        private void add(Swing swing) {
            weightedPrice += touches == 0 ? 0 : swing.price();
            if (touches == 0) weightedPrice = swing.price();
            touches++;
            if (swing.kind() == SwingKind.HIGH) highTouches++;
            else lowTouches++;
            latestIndex = Math.max(latestIndex, swing.index());
        }

        private ZoneCandidate freeze(double tolerance, int lastIndex) {
            var center = center();
            var halfWidth = tolerance * 0.55;
            var recency = Math.max(0, 30 - Math.max(0, lastIndex - latestIndex) / 8);
            var strength = Math.min(100, 30 + touches * 12 + recency + (roleFlip() ? 10 : 0));
            return new ZoneCandidate(
                    center,
                    new PriceZone(
                            round2(center * (1 - halfWidth)),
                            round2(center * (1 + halfWidth)),
                            touches,
                            strength,
                            roleFlip()
                    )
            );
        }

        private boolean roleFlip() {
            return highTouches > 0 && lowTouches > 0;
        }
    }

    private record Channel(
            int length,
            double intercept,
            double slope,
            double deviation,
            double lower,
            double mid,
            double upper,
            double positionPct,
            double annualizedSlopePct
    ) {
        private static Channel of(
                int length,
                double intercept,
                double slope,
                double deviation,
                double current
        ) {
            var last = at(length - 1, intercept, slope, deviation);
            var spread = last.upper - last.lower;
            var position = spread <= 0 ? 50 : (current - last.lower) / spread * 100;
            return new Channel(
                    length,
                    intercept,
                    slope,
                    deviation,
                    last.lower,
                    last.mid,
                    last.upper,
                    Math.max(-25, Math.min(125, position)),
                    (Math.exp(slope * 252) - 1) * 100
            );
        }

        private Channel at(int relativeIndex) {
            var value = at(relativeIndex, intercept, slope, deviation);
            var spread = value.upper - value.lower;
            return new Channel(
                    length, intercept, slope, deviation,
                    value.lower, value.mid, value.upper,
                    spread <= 0 ? 50 : positionPct,
                    annualizedSlopePct
            );
        }

        private static ChannelPoint at(int index, double intercept, double slope, double deviation) {
            var expected = intercept + slope * index;
            return new ChannelPoint(
                    Math.exp(expected - 2 * deviation),
                    Math.exp(expected),
                    Math.exp(expected + 2 * deviation)
            );
        }
    }

    private record ChannelPoint(double lower, double mid, double upper) {
    }

    private static final class WeeklyPoint {
        private java.time.LocalDate date;
        private double close;
        private Double volume;
        private double high = -Double.MAX_VALUE;
        private double low = Double.MAX_VALUE;

        private void add(BottomPatternPoint point) {
            date = point.date();
            close = point.close();
            high = Math.max(high, PriceStructurePolicy.high(point));
            low = Math.min(low, PriceStructurePolicy.low(point));
            if (point.volume() != null) volume = (volume == null ? 0 : volume) + point.volume();
        }

        private BottomPatternPoint freeze() {
            return new BottomPatternPoint(date, close, volume, high, low);
        }
    }
}
