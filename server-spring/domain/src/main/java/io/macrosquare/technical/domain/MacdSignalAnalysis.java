package io.macrosquare.technical.domain;

import java.time.LocalDate;

/**
 * Observable MACD timing evidence. It is deliberately not a probability or a
 * buy/sell decision and therefore carries no investment score.
 */
public record MacdSignalAnalysis(
        LocalDate asOf,
        Double macd,
        Double signal,
        Double histogram,
        SignalPosition position,
        ZeroRegime zeroRegime,
        CrossType latestCross,
        LocalDate crossDate,
        Integer sessionsSinceCross,
        HistogramState histogramState,
        DivergenceType divergence,
        LocalDate divergenceStartDate,
        LocalDate divergenceEndDate,
        LocalDate divergenceConfirmedDate,
        Integer sessionsSinceDivergence,
        boolean divergenceActive,
        int sourcePointCount,
        String methodology
) {
    public enum SignalPosition { ABOVE_SIGNAL, BELOW_SIGNAL, AT_SIGNAL, UNAVAILABLE }
    public enum ZeroRegime { ABOVE_ZERO, BELOW_ZERO, AT_ZERO, UNAVAILABLE }
    public enum CrossType { BULLISH_CROSS, BEARISH_CROSS, NONE, UNAVAILABLE }
    public enum HistogramState {
        EXPANDING_POSITIVE, CONTRACTING_POSITIVE, EXPANDING_NEGATIVE, CONTRACTING_NEGATIVE, FLAT, UNAVAILABLE
    }
    public enum DivergenceType { BULLISH, BEARISH, NONE, UNAVAILABLE }

    public static MacdSignalAnalysis unavailable(LocalDate asOf, int pointCount) {
        return new MacdSignalAnalysis(
                asOf, null, null, null,
                SignalPosition.UNAVAILABLE, ZeroRegime.UNAVAILABLE, CrossType.UNAVAILABLE,
                null, null, HistogramState.UNAVAILABLE, DivergenceType.UNAVAILABLE,
                null, null, null, null, false, pointCount,
                "MACD(12,26,9); insufficient valid close history"
        );
    }
}
