package io.macrosquare.research.domain.rotation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Calculates fund creation/redemption activity from official shares outstanding.
 * The bounded score is a heuristic index, not a calibrated return probability.
 */
public final class SectorFundFlowPolicy {

    public static final int MIN_HISTORY_POINTS = 21;

    public Optional<SectorFundFlowEvidence> evaluate(List<SectorFundHistoryPoint> rawHistory) {
        if (rawHistory == null || rawHistory.size() < MIN_HISTORY_POINTS) return Optional.empty();
        var byDate = new LinkedHashMap<java.time.LocalDate, SectorFundHistoryPoint>();
        rawHistory.stream().sorted(Comparator.comparing(SectorFundHistoryPoint::observedOn))
                .forEach(point -> byDate.put(point.observedOn(), point));
        var history = List.copyOf(byDate.values());
        if (history.size() < MIN_HISTORY_POINTS) return Optional.empty();

        var latest = history.getLast();
        var flow1d = dailyFlow(history.get(history.size() - 2), latest);
        var flow5d = rollingFlow(history, 5);
        var flow20d = rollingFlow(history, 20);
        var flow5dPct = flow5d / latest.totalNetAssets() * 100d;
        var flow20dPct = flow20d / latest.totalNetAssets() * 100d;
        var score = clamp((int) Math.round(50 + flow5dPct * 6 + flow20dPct * 4));
        return Optional.of(new SectorFundFlowEvidence(
                latest.observedOn(), latest.nav(), latest.sharesOutstanding(), latest.totalNetAssets(),
                flow1d, flow5d, flow20d, flow5dPct, flow20dPct, score));
    }

    private static double rollingFlow(List<SectorFundHistoryPoint> history, int intervals) {
        var start = history.size() - intervals;
        double value = 0;
        for (var index = start; index < history.size(); index++) {
            value += dailyFlow(history.get(index - 1), history.get(index));
        }
        return value;
    }

    private static double dailyFlow(SectorFundHistoryPoint previous, SectorFundHistoryPoint current) {
        return (current.sharesOutstanding() - previous.sharesOutstanding()) * current.nav();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
