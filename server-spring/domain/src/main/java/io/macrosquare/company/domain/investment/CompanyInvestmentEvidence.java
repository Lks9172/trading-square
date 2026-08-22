package io.macrosquare.company.domain.investment;

import java.util.List;
import java.util.Objects;

/**
 * Point-in-time, provider-neutral evidence used to decide whether a company is
 * healthy, reasonably priced, improving, sector-supported, and ready to enter.
 *
 * <p>No transport, persistence, cache, or vendor model is allowed in this
 * boundary. Missing evidence is represented as {@code null}; it lowers
 * confidence instead of being silently converted into a bullish neutral.</p>
 */
public record CompanyInvestmentEvidence(
        String ticker,
        ScoreEvidence scores,
        FundamentalEvidence fundamentals,
        ValuationEvidence valuation,
        CatalystEvidence catalyst,
        SectorEvidence sector,
        TimingEvidence timing,
        FundamentalsReadiness fundamentalsReadiness,
        List<HistoricalValidation> historicalValidations,
        int availableEvidenceCount,
        int expectedEvidenceCount,
        List<String> dataWarnings
) {
    public CompanyInvestmentEvidence {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        scores = Objects.requireNonNull(scores, "scores");
        fundamentals = Objects.requireNonNull(fundamentals, "fundamentals");
        valuation = Objects.requireNonNull(valuation, "valuation");
        catalyst = Objects.requireNonNull(catalyst, "catalyst");
        sector = Objects.requireNonNull(sector, "sector");
        timing = Objects.requireNonNull(timing, "timing");
        fundamentalsReadiness = defaultValue(fundamentalsReadiness, FundamentalsReadiness.UNKNOWN);
        historicalValidations = List.copyOf(
                historicalValidations == null ? List.of() : historicalValidations);
        if (availableEvidenceCount < 0) {
            throw new IllegalArgumentException("availableEvidenceCount must not be negative");
        }
        if (expectedEvidenceCount < 1 || availableEvidenceCount > expectedEvidenceCount) {
            throw new IllegalArgumentException("invalid evidence coverage");
        }
        dataWarnings = List.copyOf(dataWarnings == null ? List.of() : dataWarnings);
        ticker = ticker.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Compatibility constructor for callers created before filing-readiness became an explicit gate. */
    public CompanyInvestmentEvidence(
            String ticker,
            ScoreEvidence scores,
            FundamentalEvidence fundamentals,
            ValuationEvidence valuation,
            CatalystEvidence catalyst,
            SectorEvidence sector,
            TimingEvidence timing,
            List<HistoricalValidation> historicalValidations,
            int availableEvidenceCount,
            int expectedEvidenceCount,
            List<String> dataWarnings
    ) {
        this(
                ticker, scores, fundamentals, valuation, catalyst, sector, timing,
                FundamentalsReadiness.CURRENT, historicalValidations,
                availableEvidenceCount, expectedEvidenceCount, dataWarnings
        );
    }

    public int evidenceCoveragePct() {
        return clampScore((int) Math.round(availableEvidenceCount * 100.0 / expectedEvidenceCount));
    }

    public record ScoreEvidence(
            Integer companyScore,
            Integer growthScore,
            Integer qualityScore,
            Integer valuationScore,
            Integer balanceSheetScore,
            Integer appealScore,
            Integer crowdingScore,
            Integer legacyBuyScore
    ) {
        public ScoreEvidence {
            requireScores(
                    companyScore, growthScore, qualityScore, valuationScore,
                    balanceSheetScore, appealScore, crowdingScore, legacyBuyScore);
        }
    }

    public record FundamentalEvidence(
            Double revenueGrowthYoY,
            Double operatingMargin,
            Double operatingMarginTrend,
            Double freeCashFlowMargin,
            Double roic,
            Double roe,
            Double netDebtToRevenue,
            Double currentRatio,
            Double shareDilutionYoY,
            Double shareDilution3yCagr,
            Double stockCompToRevenue,
            Double accrualRatio,
            Integer cashConversionScore,
            Integer earningsQualityScore,
            Integer bottleneckScore,
            Integer switchingCost,
            EvidenceStrength pricingPower,
            EvidenceStrength leadTimeSignal,
            EvidenceStrength backlogSignal
    ) {
        public FundamentalEvidence {
            requireFinite(
                    revenueGrowthYoY, operatingMargin, operatingMarginTrend, freeCashFlowMargin,
                    roic, roe, netDebtToRevenue, currentRatio, shareDilutionYoY,
                    shareDilution3yCagr, stockCompToRevenue, accrualRatio);
            requireScores(cashConversionScore, earningsQualityScore, bottleneckScore, switchingCost);
            pricingPower = defaultValue(pricingPower, EvidenceStrength.UNKNOWN);
            leadTimeSignal = defaultValue(leadTimeSignal, EvidenceStrength.UNKNOWN);
            backlogSignal = defaultValue(backlogSignal, EvidenceStrength.UNKNOWN);
        }
    }

    public record ValuationEvidence(
            Double evToSales,
            Double evToFreeCashFlow,
            Double premiumPctVsPeerAverage,
            Double premiumPctVsPeerMedian,
            ValuationRangePosition internalRange,
            ValuationRelativePosition peerPosition,
            RiskBand multipleCompressionRisk,
            RiskBand rateSensitivity,
            RiskBand narrativePremium
    ) {
        public ValuationEvidence {
            requireFinite(evToSales, evToFreeCashFlow, premiumPctVsPeerAverage, premiumPctVsPeerMedian);
            internalRange = defaultValue(internalRange, ValuationRangePosition.UNKNOWN);
            peerPosition = defaultValue(peerPosition, ValuationRelativePosition.UNKNOWN);
            multipleCompressionRisk = defaultValue(multipleCompressionRisk, RiskBand.UNKNOWN);
            rateSensitivity = defaultValue(rateSensitivity, RiskBand.UNKNOWN);
            narrativePremium = defaultValue(narrativePremium, RiskBand.UNKNOWN);
        }
    }

    public record CatalystEvidence(
            Double estimateUpsidePct,
            Double estimateRevision7d,
            Double estimateRevision30d,
            Double estimateRevision90d,
            Double analystScoreRevision7d,
            Double analystScoreRevision30d,
            Double analystScoreRevision90d,
            GuidanceDirection guidanceDirection,
            Integer earningsBottomScore,
            NarrativeStage narrativeStage,
            NarrativeTrend narrativeTrend
    ) {
        public CatalystEvidence {
            requireFinite(
                    estimateUpsidePct, estimateRevision7d, estimateRevision30d, estimateRevision90d,
                    analystScoreRevision7d, analystScoreRevision30d, analystScoreRevision90d);
            requireScores(earningsBottomScore);
            guidanceDirection = defaultValue(guidanceDirection, GuidanceDirection.UNKNOWN);
            narrativeStage = defaultValue(narrativeStage, NarrativeStage.UNKNOWN);
            narrativeTrend = defaultValue(narrativeTrend, NarrativeTrend.UNKNOWN);
        }
    }

    public record SectorEvidence(
            Integer buyScore,
            Integer qualityScore,
            Integer appealScore,
            Integer crowdingScore,
            Integer valuationScore,
            Integer earningsRevisionScore,
            Integer rotationScore,
            Integer macroFitScore,
            Integer relativeStrengthScore,
            Integer fundamentalScore,
            Integer flowScore,
            SectorStance stance,
            SectorRotationState rotationState,
            MarketBias marketBias,
            String expectedLeadershipWindow
    ) {
        public SectorEvidence {
            requireScores(
                    buyScore, qualityScore, appealScore, crowdingScore, valuationScore,
                    earningsRevisionScore, rotationScore, macroFitScore,
                    relativeStrengthScore, fundamentalScore, flowScore);
            stance = defaultValue(stance, SectorStance.UNKNOWN);
            rotationState = defaultValue(rotationState, SectorRotationState.UNKNOWN);
            marketBias = defaultValue(marketBias, MarketBias.UNKNOWN);
            expectedLeadershipWindow = expectedLeadershipWindow == null ? "" : expectedLeadershipWindow.trim();
        }
    }

    public record TimingEvidence(
            Integer bottomScore,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            Integer confirmedBottomScore,
            BottomConviction bottomConviction,
            Integer reversalScore,
            ReversalState reversalState,
            Integer technicalConfirmationScore,
            TechnicalFlowState technicalFlowState,
            Integer priceStructureScore,
            PriceTrendState priceTrendState,
            PriceReversalStage priceReversalStage,
            PriceRecoveryStage priceRecoveryStage,
            PriceLocationState priceLocationState,
            MovingAverageState movingAverageState,
            Double rsi14,
            boolean oversoldConfluence,
            boolean stopHuntReclaim,
            boolean volumeBreakout,
            FibonacciSwingDirection fibonacciSwingDirection,
            FibonacciZoneState fibonacciZoneState,
            Double fibonacciNearestRatio,
            Integer fibonacciConfluenceScore,
            boolean fibonacciWeeklyConfluence,
            boolean fibonacciSupportConfluence,
            Integer correctionScore,
            Integer trendBreakRiskScore,
            Integer shortTermScore,
            Integer swingTermScore,
            Integer longTermScore,
            ThesisState thesisState,
            Integer quoteAgeDays,
            Integer fundamentalsAgeDays
    ) {
        public TimingEvidence {
            requireScores(
                    bottomScore, priceBottomScore, volumeConfirmationScore, failureRiskScore,
                    confirmedBottomScore, reversalScore, technicalConfirmationScore,
                    priceStructureScore, fibonacciConfluenceScore, correctionScore,
                    trendBreakRiskScore, shortTermScore, swingTermScore, longTermScore);
            requireFinite(rsi14, fibonacciNearestRatio);
            requireNonNegative(quoteAgeDays, "quoteAgeDays");
            requireNonNegative(fundamentalsAgeDays, "fundamentalsAgeDays");
            bottomConviction = defaultValue(bottomConviction, BottomConviction.UNKNOWN);
            reversalState = defaultValue(reversalState, ReversalState.UNKNOWN);
            technicalFlowState = defaultValue(technicalFlowState, TechnicalFlowState.UNKNOWN);
            priceTrendState = defaultValue(priceTrendState, PriceTrendState.UNKNOWN);
            priceReversalStage = defaultValue(priceReversalStage, PriceReversalStage.UNKNOWN);
            priceRecoveryStage = defaultValue(priceRecoveryStage, PriceRecoveryStage.UNKNOWN);
            priceLocationState = defaultValue(priceLocationState, PriceLocationState.UNKNOWN);
            movingAverageState = defaultValue(movingAverageState, MovingAverageState.UNKNOWN);
            fibonacciSwingDirection = defaultValue(fibonacciSwingDirection, FibonacciSwingDirection.UNKNOWN);
            fibonacciZoneState = defaultValue(fibonacciZoneState, FibonacciZoneState.UNKNOWN);
            thesisState = defaultValue(thesisState, ThesisState.UNKNOWN);
        }

        /** Backward-compatible constructor for producers with price structure but no Fibonacci evidence. */
        public TimingEvidence(
                Integer bottomScore,
                Integer priceBottomScore,
                Integer volumeConfirmationScore,
                Integer failureRiskScore,
                Integer confirmedBottomScore,
                BottomConviction bottomConviction,
                Integer reversalScore,
                ReversalState reversalState,
                Integer technicalConfirmationScore,
                TechnicalFlowState technicalFlowState,
                Integer priceStructureScore,
                PriceTrendState priceTrendState,
                PriceReversalStage priceReversalStage,
                PriceRecoveryStage priceRecoveryStage,
                PriceLocationState priceLocationState,
                MovingAverageState movingAverageState,
                Double rsi14,
                boolean oversoldConfluence,
                boolean stopHuntReclaim,
                boolean volumeBreakout,
                Integer correctionScore,
                Integer trendBreakRiskScore,
                Integer shortTermScore,
                Integer swingTermScore,
                Integer longTermScore,
                ThesisState thesisState,
                Integer quoteAgeDays,
                Integer fundamentalsAgeDays
        ) {
            this(
                    bottomScore, priceBottomScore, volumeConfirmationScore, failureRiskScore,
                    confirmedBottomScore, bottomConviction, reversalScore, reversalState,
                    technicalConfirmationScore, technicalFlowState,
                    priceStructureScore, priceTrendState, priceReversalStage,
                    priceRecoveryStage, priceLocationState, movingAverageState,
                    rsi14, oversoldConfluence, stopHuntReclaim, volumeBreakout,
                    FibonacciSwingDirection.UNKNOWN, FibonacciZoneState.UNKNOWN,
                    null, null, false, false,
                    correctionScore, trendBreakRiskScore, shortTermScore, swingTermScore,
                    longTermScore, thesisState, quoteAgeDays, fundamentalsAgeDays
            );
        }

        /** Backward-compatible constructor for evidence producers that do not yet expose structure. */
        public TimingEvidence(
                Integer bottomScore,
                Integer priceBottomScore,
                Integer volumeConfirmationScore,
                Integer failureRiskScore,
                Integer confirmedBottomScore,
                BottomConviction bottomConviction,
                Integer reversalScore,
                ReversalState reversalState,
                Integer technicalConfirmationScore,
                TechnicalFlowState technicalFlowState,
                Integer correctionScore,
                Integer trendBreakRiskScore,
                Integer shortTermScore,
                Integer swingTermScore,
                Integer longTermScore,
                ThesisState thesisState,
                Integer quoteAgeDays,
                Integer fundamentalsAgeDays
        ) {
            this(
                    bottomScore, priceBottomScore, volumeConfirmationScore, failureRiskScore,
                    confirmedBottomScore, bottomConviction, reversalScore, reversalState,
                    technicalConfirmationScore, technicalFlowState,
                    null, PriceTrendState.UNKNOWN, PriceReversalStage.UNKNOWN,
                    PriceRecoveryStage.UNKNOWN, PriceLocationState.UNKNOWN,
                    MovingAverageState.UNKNOWN, null, false, false, false,
                    FibonacciSwingDirection.UNKNOWN, FibonacciZoneState.UNKNOWN,
                    null, null, false, false,
                    correctionScore, trendBreakRiskScore, shortTermScore, swingTermScore,
                    longTermScore, thesisState, quoteAgeDays, fundamentalsAgeDays
            );
        }
    }

    public record HistoricalValidation(
            String sourceHorizon,
            int forwardTradingDays,
            int signalCount,
            Double positiveHitRatePct,
            Double targetReturnPct,
            Double targetHitRatePct,
            Double averageReturnPct,
            Double averageMaxDrawdownPct
    ) {
        public HistoricalValidation {
            sourceHorizon = sourceHorizon == null ? "" : sourceHorizon.trim();
            if (forwardTradingDays < 1) {
                throw new IllegalArgumentException("forwardTradingDays must be positive");
            }
            if (signalCount < 0) throw new IllegalArgumentException("signalCount must not be negative");
            requirePercent(positiveHitRatePct, "positiveHitRatePct");
            requirePercent(targetHitRatePct, "targetHitRatePct");
            requireFinite(targetReturnPct, averageReturnPct, averageMaxDrawdownPct);
        }
    }

    public enum EvidenceStrength {
        WEAK,
        MODERATE,
        STRONG,
        UNKNOWN
    }

    /** Whether normalized financials cover the latest known periodic filing. */
    public enum FundamentalsReadiness {
        CURRENT,
        LAGGING,
        INCOMPLETE,
        PENDING,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum GuidanceDirection {
        RAISED,
        AFFIRMED,
        MIXED,
        LOWERED,
        UNKNOWN
    }

    public enum NarrativeStage {
        EARLY,
        MID,
        OVERHEATED,
        UNKNOWN
    }

    public enum NarrativeTrend {
        HEATING,
        STABLE,
        COOLING,
        UNKNOWN
    }

    public enum ValuationRangePosition {
        UNDERVALUED,
        FAIR,
        OVERVALUED,
        UNKNOWN
    }

    public enum ValuationRelativePosition {
        DISCOUNT,
        NEUTRAL,
        PREMIUM,
        UNKNOWN
    }

    public enum RiskBand {
        LOW,
        MODERATE,
        HIGH,
        UNKNOWN
    }

    public enum SectorStance {
        FAVORED,
        NEUTRAL,
        AVOIDED,
        UNKNOWN
    }

    public enum SectorRotationState {
        LEADING,
        IMPROVING,
        WEAKENING,
        LAGGING,
        UNKNOWN
    }

    public enum MarketBias {
        STRONG_BUY,
        BUY,
        HOLD,
        REDUCE,
        SELL,
        UNKNOWN
    }

    public enum BottomConviction {
        CONVICTION,
        CANDIDATE,
        UNMET,
        UNKNOWN
    }

    public enum ReversalState {
        STRONG,
        ON,
        EARLY,
        OFF,
        UNKNOWN
    }

    public enum TechnicalFlowState {
        ACCUMULATION,
        NEUTRAL,
        DISTRIBUTION,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum PriceTrendState {
        UPTREND,
        RANGE,
        DOWNTREND,
        TRANSITION,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum PriceReversalStage {
        INTACT,
        MOMENTUM_WEAKENING,
        STRUCTURAL_CRACK,
        PRIOR_LOW_BROKEN,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum PriceRecoveryStage {
        NONE,
        BASE_BUILDING,
        REBOUND,
        STRUCTURE_BREAK,
        RETEST_HELD,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum PriceLocationState {
        BREAKOUT,
        LOWER_CHANNEL,
        SUPPORT_ZONE,
        MID_CHANNEL,
        RESISTANCE_ZONE,
        UPPER_CHANNEL,
        BREAKDOWN,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum MovingAverageState {
        BULLISH_ALIGNED,
        CONVERGED,
        TRANSITION,
        BEARISH_ALIGNED,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum FibonacciSwingDirection {
        UP_SWING,
        DOWN_SWING,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum FibonacciZoneState {
        EXTENSION,
        SHALLOW_RETRACEMENT,
        MODERATE_RETRACEMENT,
        DEEP_RETRACEMENT,
        LAST_DEFENSE,
        LAST_DEFENSE_BROKEN,
        UNAVAILABLE,
        UNKNOWN
    }

    public enum ThesisState {
        INTACT,
        WEAKENED,
        BREAK_RISK,
        UNKNOWN
    }

    private static <T> T defaultValue(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static void requireScores(Integer... values) {
        for (var value : values) {
            if (value != null && (value < 0 || value > 100)) {
                throw new IllegalArgumentException("score must be between 0 and 100");
            }
        }
    }

    private static void requireFinite(Double... values) {
        for (var value : values) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("numeric evidence must be finite");
            }
        }
    }

    private static void requirePercent(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }

    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
