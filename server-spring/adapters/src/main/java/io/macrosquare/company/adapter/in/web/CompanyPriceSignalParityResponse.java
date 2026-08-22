package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot;
import io.macrosquare.company.application.port.in.CompanyPriceSignalParityReport;
import io.macrosquare.company.domain.bottom.BottomPatternAnalysis;
import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPriceContext;
import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.VolumePriceAnalysis;
import io.macrosquare.company.domain.horizon.CompanyWalkForwardValidation;
import io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis;
import io.macrosquare.technical.domain.MacdSignalAnalysis;

import java.time.LocalDate;
import java.util.List;

public record CompanyPriceSignalParityResponse(
        String ticker,
        int lookbackDays,
        boolean allMatched,
        boolean priceHistoryMatched,
        boolean markersMatched,
        boolean priceSignalMatched,
        boolean confirmedBottomMatched,
        boolean reversalConfirmationMatched,
        boolean legacyAvailable,
        List<String> differences,
        ComparisonResponse<SignalSnapshotResponse> result,
        PriceContextResponse springContext
) {
    static CompanyPriceSignalParityResponse from(CompanyPriceSignalParityReport report) {
        return new CompanyPriceSignalParityResponse(
                report.ticker(),
                report.lookbackDays(),
                report.allMatched(),
                report.priceHistoryMatched(),
                report.markersMatched(),
                report.priceSignalMatched(),
                report.confirmedBottomMatched(),
                report.reversalConfirmationMatched(),
                report.legacyAvailable(),
                report.differences(),
                new ComparisonResponse<>(
                        SignalSnapshotResponse.from(report.legacy()),
                        SignalSnapshotResponse.from(report.spring())
                ),
                PriceContextResponse.from(report.springContext())
        );
    }

    public record ComparisonResponse<T>(T legacy, T spring) {
    }

    public record SignalSnapshotResponse(
            HistorySummaryResponse history,
            List<ChartMarkerResponse> markers,
            PriceSignalResponse priceSignal,
            DeepBottomResponse confirmedBottom,
            ReversalResponse reversalConfirmation,
            TechnicalConfirmationResponse technicalConfirmation,
            WalkForwardResponse walkForwardValidation,
            PriceStructureResponse priceStructure,
            MacdMultiTimeframeResponse macdMomentum
    ) {
        static SignalSnapshotResponse from(CompanyPriceSignalSnapshot value) {
            return new SignalSnapshotResponse(
                    HistorySummaryResponse.from(value.history()),
                    value.markers().stream().map(ChartMarkerResponse::from).toList(),
                    PriceSignalResponse.from(value.priceSignal()),
                    DeepBottomResponse.from(value.confirmedBottom()),
                    ReversalResponse.from(value.reversalConfirmation()),
                    TechnicalConfirmationResponse.fromNullable(value.technicalConfirmation()),
                    WalkForwardResponse.fromNullable(value.walkForwardValidation()),
                    PriceStructureResponse.fromNullable(value.priceStructure()),
                    MacdMultiTimeframeResponse.fromNullable(value.macdMomentum())
            );
        }
    }

    public record MacdMultiTimeframeResponse(
            MacdSignalResponse daily,
            MacdSignalResponse weekly,
            boolean currentWeekProvisional
    ) {
        static MacdMultiTimeframeResponse fromNullable(MacdMultiTimeframeAnalysis value) {
            return value == null ? null : new MacdMultiTimeframeResponse(
                    MacdSignalResponse.from(value.daily()),
                    MacdSignalResponse.from(value.weekly()),
                    value.currentWeekProvisional()
            );
        }
    }

    public record MacdSignalResponse(
            LocalDate asOf,
            Double macd,
            Double signal,
            Double histogram,
            String position,
            String zeroRegime,
            String latestCross,
            LocalDate crossDate,
            Integer sessionsSinceCross,
            String histogramState,
            String divergence,
            LocalDate divergenceStartDate,
            LocalDate divergenceEndDate,
            LocalDate divergenceConfirmedDate,
            Integer sessionsSinceDivergence,
            boolean divergenceActive,
            int sourcePointCount,
            String methodology
    ) {
        static MacdSignalResponse from(MacdSignalAnalysis value) {
            return new MacdSignalResponse(
                    value.asOf(), value.macd(), value.signal(), value.histogram(),
                    value.position().name(), value.zeroRegime().name(), value.latestCross().name(),
                    value.crossDate(), value.sessionsSinceCross(), value.histogramState().name(),
                    value.divergence().name(), value.divergenceStartDate(), value.divergenceEndDate(),
                    value.divergenceConfirmedDate(), value.sessionsSinceDivergence(),
                    value.divergenceActive(), value.sourcePointCount(), value.methodology()
            );
        }
    }

    public record PriceStructureResponse(
            int score,
            String trendState,
            String bearishReversalStage,
            String recoveryStage,
            String priceLocation,
            String movingAverageState,
            Double rsi14,
            Double sma20,
            Double sma50,
            Double sma100,
            Double sma200,
            Double movingAverageConvergencePct,
            Double channelLower,
            Double channelMid,
            Double channelUpper,
            Double channelPositionPct,
            Double channelAnnualizedSlopePct,
            PriceZoneResponse supportZone,
            PriceZoneResponse resistanceZone,
            int consolidationDays,
            Double consolidationRangePct,
            boolean volumeBreakout,
            boolean stopHuntReclaim,
            boolean oversoldConfluence,
            FibonacciResponse fibonacci,
            List<String> reasons,
            List<String> cautions,
            String methodology
    ) {
        static PriceStructureResponse fromNullable(PriceStructureAnalysis value) {
            return value == null ? null : new PriceStructureResponse(
                    value.score(),
                    value.trendState().name(),
                    value.bearishReversalStage().name(),
                    value.recoveryStage().name(),
                    value.priceLocation().name(),
                    value.movingAverageState().name(),
                    value.rsi14(),
                    value.sma20(),
                    value.sma50(),
                    value.sma100(),
                    value.sma200(),
                    value.movingAverageConvergencePct(),
                    value.channelLower(),
                    value.channelMid(),
                    value.channelUpper(),
                    value.channelPositionPct(),
                    value.channelAnnualizedSlopePct(),
                    PriceZoneResponse.fromNullable(value.supportZone()),
                    PriceZoneResponse.fromNullable(value.resistanceZone()),
                    value.consolidationDays(),
                    value.consolidationRangePct(),
                    value.volumeBreakout(),
                    value.stopHuntReclaim(),
                    value.oversoldConfluence(),
                    FibonacciResponse.from(value.fibonacci()),
                    value.reasons(),
                    value.cautions(),
                    value.methodology()
            );
        }
    }

    public record FibonacciResponse(
            String swingDirection,
            LocalDate swingStartDate,
            LocalDate swingEndDate,
            Double swingStartPrice,
            Double swingEndPrice,
            Double swingAmplitudePct,
            Double currentPrice,
            Double currentRetracementRatio,
            List<FibonacciLevelResponse> levels,
            Double nearestRatio,
            Double nearestPrice,
            Double nearestGapPct,
            String timeframeReliability,
            boolean weeklyConfluence,
            boolean supportResistanceConfluence,
            boolean channelConfluence,
            int confluenceScore,
            String zoneState,
            String summary,
            List<String> cautions,
            String methodology
    ) {
        static FibonacciResponse from(FibonacciRetracementAnalysis value) {
            return new FibonacciResponse(
                    value.swingDirection().name(),
                    value.swingStartDate(),
                    value.swingEndDate(),
                    value.swingStartPrice(),
                    value.swingEndPrice(),
                    value.swingAmplitudePct(),
                    value.currentPrice(),
                    value.currentRetracementRatio(),
                    value.levels().stream()
                            .map(level -> new FibonacciLevelResponse(
                                    level.ratio(), level.price(), level.label()))
                            .toList(),
                    value.nearestRatio(),
                    value.nearestPrice(),
                    value.nearestGapPct(),
                    value.timeframeReliability().name(),
                    value.weeklyConfluence(),
                    value.supportResistanceConfluence(),
                    value.channelConfluence(),
                    value.confluenceScore(),
                    value.zoneState().name(),
                    value.summary(),
                    value.cautions(),
                    value.methodology()
            );
        }
    }

    public record FibonacciLevelResponse(double ratio, double price, String label) {
    }

    public record PriceZoneResponse(
            double lower,
            double upper,
            int touches,
            int strength,
            boolean roleFlip
    ) {
        static PriceZoneResponse fromNullable(PriceStructureAnalysis.PriceZone value) {
            return value == null ? null : new PriceZoneResponse(
                    value.lower(), value.upper(), value.touches(), value.strength(), value.roleFlip());
        }
    }

    public record TechnicalConfirmationResponse(
            int score,
            String state,
            Double vwap20,
            Double closeVsVwap20Pct,
            Double vwapSlope5dPct,
            Double obvPressure20Pct,
            List<String> reasons,
            List<String> cautions
    ) {
        static TechnicalConfirmationResponse fromNullable(VolumePriceAnalysis value) {
            return value == null ? null : new TechnicalConfirmationResponse(
                    value.score(), value.state().name(), value.vwap20(), value.closeVsVwap20Pct(),
                    value.vwapSlope5dPct(), value.obvPressure20Pct(), value.reasons(), value.cautions());
        }
    }

    public record WalkForwardResponse(
            LocalDate firstDate,
            LocalDate lastDate,
            int historyPointCount,
            String methodology,
            List<WalkForwardMetricResponse> horizons
    ) {
        static WalkForwardResponse fromNullable(CompanyWalkForwardValidation value) {
            return value == null ? null : new WalkForwardResponse(
                    value.firstDate(), value.lastDate(), value.historyPointCount(), value.methodology(),
                    value.horizons().stream().map(item -> new WalkForwardMetricResponse(
                            item.horizon().name(), item.forwardTradingDays(), item.targetReturnPct(),
                            item.signalThreshold(), item.signalCount(), item.positiveHitRatePct(),
                            item.targetHitRatePct(), item.averageReturnPct(), item.medianReturnPct(),
                            item.averageDaysToTarget(), item.averageMaxDrawdownPct())).toList()
            );
        }
    }

    public record WalkForwardMetricResponse(
            String horizon,
            int forwardTradingDays,
            double targetReturnPct,
            int signalThreshold,
            int signalCount,
            Double positiveHitRatePct,
            Double targetHitRatePct,
            Double averageReturnPct,
            Double medianReturnPct,
            Double averageDaysToTarget,
            Double averageMaxDrawdownPct
    ) {
    }

    public record HistorySummaryResponse(
            int pointCount,
            LocalDate firstDate,
            Double firstClose,
            LocalDate lastDate,
            Double lastClose
    ) {
        static HistorySummaryResponse from(CompanyPriceSignalSnapshot.PriceHistorySummary value) {
            return new HistorySummaryResponse(
                    value.pointCount(), value.firstDate(), value.firstClose(), value.lastDate(), value.lastClose()
            );
        }
    }

    public record ChartMarkerResponse(String kind, LocalDate date, double value) {
        static ChartMarkerResponse from(CompanyPriceSignalSnapshot.ChartMarker value) {
            return new ChartMarkerResponse(value.kind(), value.date(), value.value());
        }
    }

    public record PriceSignalResponse(
            int priceResetScore,
            int patternScore,
            int absorptionScore,
            int volumeConfirmationScore,
            int priceBottomScore,
            int failureRiskScore,
            String structureState
    ) {
        static PriceSignalResponse from(BottomPriceSignal value) {
            return new PriceSignalResponse(
                    value.priceResetScore(), value.patternScore(), value.absorptionScore(),
                    value.volumeConfirmationScore(), value.priceBottomScore(), value.failureRiskScore(),
                    value.structureState().name()
            );
        }
    }

    public record DeepBottomResponse(
            int score,
            String state,
            String actionBias,
            LocalDate signalDate,
            Integer daysSinceSignal,
            String summary,
            Double recentVolumeRatio,
            Double contractionRatio,
            Double drawdown120dPct,
            Double ma20GapPct,
            Double recentDrop3dPct,
            List<String> reasons,
            List<String> cautions
    ) {
        static DeepBottomResponse from(DeepBottomSignal value) {
            return new DeepBottomResponse(
                    value.score(), value.state().name(), value.actionBias().name(), value.signalDate(),
                    value.daysSinceSignal(), value.summary(), value.recentVolumeRatio(), value.contractionRatio(),
                    value.drawdown120dPct(), value.ma20GapPct(), value.recentDrop3dPct(),
                    value.reasons(), value.cautions()
            );
        }
    }

    public record ReversalResponse(
            String status,
            int score,
            LocalDate signalDate,
            String summary,
            List<String> reasons,
            List<String> cautions
    ) {
        static ReversalResponse from(ReversalConfirmation value) {
            return new ReversalResponse(
                    value.status().name(), value.score(), value.signalDate(), value.summary(),
                    value.reasons(), value.cautions()
            );
        }
    }

    public record PriceContextResponse(
            Double drawdownFromHighPct,
            Double drawdownFrom120dHighPct,
            Double reboundFromLowPct,
            Double return30dPct,
            Double volumeTrend20dPct,
            Double ma20GapPct,
            boolean ma20Below50,
            Double recentDrop3dPct,
            Double candidateVolumeRatio,
            Double confirmVolumeRatio,
            Double retestVolumeRatio,
            Double absorptionVolumeVsRecent2dRatio,
            Double absorptionVolumeVsRecent3dRatio,
            Double absorptionDropPct,
            Double priorDeclineDropPct,
            Double absorptionContractionRatio,
            LocalDate absorptionDate,
            Integer daysSinceAbsorption,
            Double reboundSinceAbsorptionPct,
            PatternResponse pattern,
            List<PricePointResponse> chartPoints
    ) {
        static PriceContextResponse from(BottomPriceContext value) {
            return new PriceContextResponse(
                    value.drawdownFromHighPct(), value.drawdownFrom120dHighPct(), value.reboundFromLowPct(),
                    value.return30dPct(), value.volumeTrend20dPct(), value.ma20GapPct(), value.ma20Below50(),
                    value.recentDrop3dPct(), value.candidateVolumeRatio(), value.confirmVolumeRatio(),
                    value.retestVolumeRatio(), value.absorptionVolumeVsRecent2dRatio(),
                    value.absorptionVolumeVsRecent3dRatio(), value.absorptionDropPct(),
                    value.priorDeclineDropPct(), value.absorptionContractionRatio(), value.absorptionDate(),
                    value.daysSinceAbsorption(), value.reboundSinceAbsorptionPct(),
                    PatternResponse.from(value.pattern()),
                    value.chartPoints().stream().map(PricePointResponse::from).toList()
            );
        }
    }

    public record PatternResponse(
            PricePointResponse peakPoint,
            PricePointResponse candidatePoint,
            PricePointResponse retestPoint,
            PricePointResponse confirmPoint,
            PricePointResponse currentPoint,
            String phase,
            Double declinePctFromPeak,
            Double reboundPctFromCandidate,
            Double retestGapPct
    ) {
        static PatternResponse from(BottomPatternAnalysis value) {
            return new PatternResponse(
                    PricePointResponse.fromNullable(value.peakPoint()),
                    PricePointResponse.fromNullable(value.candidatePoint()),
                    PricePointResponse.fromNullable(value.retestPoint()),
                    PricePointResponse.fromNullable(value.confirmPoint()),
                    PricePointResponse.fromNullable(value.currentPoint()),
                    value.phase().name(), value.declinePctFromPeak(), value.reboundPctFromCandidate(),
                    value.retestGapPct()
            );
        }
    }

    public record PricePointResponse(LocalDate date, double close, Double volume, Double high, Double low) {
        static PricePointResponse from(BottomPatternPoint value) {
            return new PricePointResponse(value.date(), value.close(), value.volume(), value.high(), value.low());
        }

        static PricePointResponse fromNullable(BottomPatternPoint value) {
            return value == null ? null : from(value);
        }
    }
}
