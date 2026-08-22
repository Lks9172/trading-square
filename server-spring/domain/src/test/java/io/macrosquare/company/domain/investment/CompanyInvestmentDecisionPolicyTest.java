package io.macrosquare.company.domain.investment;

import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyInvestmentAction;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyOpportunityType;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.OutlookMethod;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.BottomConviction;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.CatalystEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.EvidenceStrength;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FundamentalEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FundamentalsReadiness;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FibonacciSwingDirection;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FibonacciZoneState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.GuidanceDirection;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.HistoricalValidation;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.MarketBias;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.MovingAverageState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.NarrativeStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.NarrativeTrend;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceLocationState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceRecoveryStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceReversalStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceTrendState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ReversalState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.RiskBand;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ScoreEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorRotationState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorStance;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.TechnicalFlowState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ThesisState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.TimingEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationRangePosition;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationRelativePosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyInvestmentDecisionPolicyTest {

    private final CompanyInvestmentDecisionPolicy policy = new CompanyInvestmentDecisionPolicy();

    @Test
    void latestPeriodicFilingLagCannotAuthorizeABuyEvenWhenCalendarAgeLooksFresh() {
        var base = strongEvidence(List.of());
        var lagging = new CompanyInvestmentEvidence(
                base.ticker(), base.scores(), base.fundamentals(), base.valuation(),
                base.catalyst(), base.sector(), base.timing(), FundamentalsReadiness.LAGGING,
                base.historicalValidations(), base.availableEvidenceCount(),
                base.expectedEvidenceCount(), List.of("최신 공시보다 재무 계산이 뒤처짐")
        );

        var decision = policy.evaluate(lagging);

        assertEquals(CompanyInvestmentAction.HOLD, decision.action());
        assertEquals(0, decision.entryStrategy().initialEntryPctOfTarget());
        assertTrue(decision.scaleInEligibility().blockers().stream()
                .anyMatch(value -> value.contains("최신 확인 공시")));
    }

    @Test
    void requiresQualityValueCatalystSectorAndTimingBeforeStrongBuy() {
        var decision = policy.evaluate(strongEvidence(List.of()));

        assertEquals(CompanyInvestmentAction.STRONG_BUY, decision.action());
        assertEquals(CompanyOpportunityType.QUALITY_AT_REASONABLE_PRICE, decision.opportunityType());
        assertTrue(decision.investmentMeritScore() >= 77);
        assertTrue(decision.entryReadinessScore() >= 70);
        assertTrue(decision.whyNow().size() >= 3);
        assertEquals(40, decision.entryStrategy().initialEntryPctOfTarget());
    }

    @Test
    void sizesAnUptrendSupportEntryAtFortyPercentButKeepsStructureConfirmationsForAdds() {
        var base = strongEvidence(List.of());
        var evidence = withTiming(base, new TimingEvidence(
                76, 74, 82, 28, 79, BottomConviction.CONVICTION,
                84, ReversalState.STRONG, 78, TechnicalFlowState.ACCUMULATION,
                84, PriceTrendState.UPTREND, PriceReversalStage.INTACT,
                PriceRecoveryStage.RETEST_HELD, PriceLocationState.SUPPORT_ZONE,
                MovingAverageState.BULLISH_ALIGNED, 43.0, false, false, false,
                78, 26, 80, 78, 76, ThesisState.INTACT, 1, 45
        ));

        var decision = policy.evaluate(evidence);

        assertEquals(CompanyInvestmentAction.STRONG_BUY, decision.action());
        assertEquals(40, decision.entryStrategy().initialEntryPctOfTarget());
        assertEquals(60, decision.entryStrategy().reservePctOfTarget());
        assertTrue(decision.entryStrategy().addConditions().stream()
                .anyMatch(value -> value.contains("스윙 고점")));
    }

    @Test
    void priorLowBreakPreventsAHighScoreFromRemainingABuy() {
        var base = strongEvidence(List.of());
        var evidence = withTiming(base, new TimingEvidence(
                76, 74, 82, 28, 79, BottomConviction.CONVICTION,
                84, ReversalState.STRONG, 78, TechnicalFlowState.ACCUMULATION,
                18, PriceTrendState.DOWNTREND, PriceReversalStage.PRIOR_LOW_BROKEN,
                PriceRecoveryStage.NONE, PriceLocationState.BREAKDOWN,
                MovingAverageState.BEARISH_ALIGNED, 24.0, false, false, false,
                78, 26, 80, 78, 76, ThesisState.INTACT, 1, 45
        ));

        var decision = policy.evaluate(evidence);

        assertTrue(decision.action() == CompanyInvestmentAction.REDUCE
                || decision.action() == CompanyInvestmentAction.SELL);
        assertEquals(0, decision.entryStrategy().initialEntryPctOfTarget());
        assertTrue(decision.risk().reasons().stream().anyMatch(value -> value.contains("이전 저점")));
    }

    @Test
    void capsAHealthyButExpensiveUnconfirmedCompanyAtHold() {
        var base = strongEvidence(List.of());
        var evidence = new CompanyInvestmentEvidence(
                base.ticker(),
                new ScoreEvidence(78, 82, 84, 28, 80, 72, 78, 54),
                base.fundamentals(),
                new ValuationEvidence(
                        16.0, 68.0, 62.0, 70.0,
                        ValuationRangePosition.OVERVALUED,
                        ValuationRelativePosition.PREMIUM,
                        RiskBand.HIGH, RiskBand.HIGH, RiskBand.HIGH),
                base.catalyst(),
                new SectorEvidence(
                        68, 75, 67, 76, 38, 70, 71, 74, 82, 72, 67,
                        SectorStance.FAVORED, SectorRotationState.LEADING, MarketBias.BUY, "now"),
                new TimingEvidence(
                        50, 48, 35, 62, 38, BottomConviction.UNMET,
                        34, ReversalState.OFF, 39, TechnicalFlowState.DISTRIBUTION,
                        55, 52, 45, 48, 65, ThesisState.INTACT, 1, 60),
                List.of(),
                58,
                65,
                List.of()
        );

        var decision = policy.evaluate(evidence);

        assertEquals(CompanyInvestmentAction.HOLD, decision.action());
        assertEquals(CompanyOpportunityType.QUALITY_BUT_EXPENSIVE, decision.opportunityType());
        assertTrue(decision.whyWait().stream().anyMatch(value -> value.contains("반전")));
    }

    @Test
    void rejectsAValueTrapWithLoweredGuidanceAndBrokenThesis() {
        var base = strongEvidence(List.of());
        var evidence = new CompanyInvestmentEvidence(
                base.ticker(),
                new ScoreEvidence(48, 32, 40, 84, 52, 55, 40, 62),
                new FundamentalEvidence(
                        -8.0, 4.0, -5.0, -3.0, 2.0, 3.0, 1.3, 0.8,
                        7.0, 8.0, 22.0, 12.0, 28, 24, 35, 3,
                        EvidenceStrength.WEAK, EvidenceStrength.WEAK, EvidenceStrength.WEAK),
                new ValuationEvidence(
                        1.8, 12.0, -35.0, -30.0,
                        ValuationRangePosition.UNDERVALUED,
                        ValuationRelativePosition.DISCOUNT,
                        RiskBand.MODERATE, RiskBand.MODERATE, RiskBand.LOW),
                new CatalystEvidence(
                        38.0, -5.0, -9.0, -14.0, 0.2, 0.3, 0.4,
                        GuidanceDirection.LOWERED, 28, NarrativeStage.MID, NarrativeTrend.COOLING),
                new SectorEvidence(
                        42, 48, 45, 35, 82, 28, 35, 42, 30, 38, 30,
                        SectorStance.AVOIDED, SectorRotationState.LAGGING, MarketBias.REDUCE, ""),
                new TimingEvidence(
                        42, 45, 30, 74, 45, BottomConviction.CANDIDATE,
                        30, ReversalState.OFF, 25, TechnicalFlowState.DISTRIBUTION,
                        30, 82, 32, 38, 45, ThesisState.BREAK_RISK, 1, 75),
                List.of(),
                60,
                65,
                List.of()
        );

        var decision = policy.evaluate(evidence);

        assertEquals(CompanyInvestmentAction.SELL, decision.action());
        assertEquals(CompanyOpportunityType.VALUE_TRAP_RISK, decision.opportunityType());
        assertTrue(decision.risk().score() >= 76);
        assertEquals(
                CompanyInvestmentDecision.ScaleInEligibilityState.INELIGIBLE,
                decision.scaleInEligibility().state()
        );
        assertEquals(0, decision.scaleInEligibility().portfolioConcentrationCapPct());
    }

    @Test
    void forbidsAveragingDownAfterTheMajorSwingLastDefenseAndDowntrendBreakTogether() {
        var base = strongEvidence(List.of());
        var evidence = withTiming(base, new TimingEvidence(
                76, 74, 82, 28, 79, BottomConviction.CONVICTION,
                84, ReversalState.STRONG, 78, TechnicalFlowState.ACCUMULATION,
                58, PriceTrendState.DOWNTREND, PriceReversalStage.INTACT,
                PriceRecoveryStage.REBOUND, PriceLocationState.SUPPORT_ZONE,
                MovingAverageState.TRANSITION, 34.0, false, false, false,
                FibonacciSwingDirection.UP_SWING, FibonacciZoneState.LAST_DEFENSE_BROKEN,
                0.82, 75, true, true,
                78, 26, 80, 78, 76, ThesisState.INTACT, 1, 45
        ));

        var decision = policy.evaluate(evidence);

        assertEquals(
                CompanyInvestmentDecision.ScaleInEligibilityState.INELIGIBLE,
                decision.scaleInEligibility().state()
        );
        assertEquals(CompanyInvestmentAction.HOLD, decision.action());
        assertEquals(0, decision.entryStrategy().initialEntryPctOfTarget());
        assertTrue(decision.scaleInEligibility().blockers().stream()
                .anyMatch(value -> value.contains("0.786")));
    }

    @Test
    void doesNotPresentScoreHeuristicAsWalkForwardProbability() {
        var decision = policy.evaluate(strongEvidence(List.of()));

        assertTrue(decision.forwardOutlooks().stream()
                .allMatch(value -> value.method() == OutlookMethod.SCORE_HEURISTIC));
        assertTrue(decision.forwardOutlooks().stream()
                .allMatch(value -> value.sampleCount() == 0 && value.targetHitLikelihoodPct() == null));
    }

    @Test
    void usesPointInTimeWalkForwardSamplesWhenAvailable() {
        var validations = List.of(
                validation("SHORT_TERM", 10, 28, 64.3, 5.0, 46.4, 2.8, -5.1),
                validation("SWING_TERM", 30, 24, 70.8, 10.0, 45.8, 6.7, -8.4),
                validation("LONG_TERM", 120, 18, 77.8, 20.0, 55.6, 16.2, -13.0)
        );

        var decision = policy.evaluate(strongEvidence(validations));

        assertTrue(decision.forwardOutlooks().stream()
                .allMatch(value -> value.method() == OutlookMethod.WALK_FORWARD));
        assertEquals(28, decision.forwardOutlooks().getFirst().sampleCount());
        assertEquals(64.3, decision.forwardOutlooks().getFirst().positiveReturnLikelihoodPct());
    }

    @Test
    void lowCoveragePreventsAnOverconfidentBuy() {
        var base = strongEvidence(List.of());
        var sparse = new CompanyInvestmentEvidence(
                base.ticker(),
                new ScoreEvidence(88, null, 90, null, null, null, null, null),
                new FundamentalEvidence(
                        null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null,
                        EvidenceStrength.UNKNOWN, EvidenceStrength.UNKNOWN, EvidenceStrength.UNKNOWN),
                new ValuationEvidence(
                        null, null, null, null,
                        ValuationRangePosition.UNKNOWN,
                        ValuationRelativePosition.UNKNOWN,
                        RiskBand.UNKNOWN, RiskBand.UNKNOWN, RiskBand.UNKNOWN),
                new CatalystEvidence(
                        null, null, null, null, null, null, null,
                        GuidanceDirection.UNKNOWN, null, NarrativeStage.UNKNOWN, NarrativeTrend.UNKNOWN),
                new SectorEvidence(
                        null, null, null, null, null, null, null, null, null, null, null,
                        SectorStance.UNKNOWN, SectorRotationState.UNKNOWN, MarketBias.UNKNOWN, ""),
                new TimingEvidence(
                        null, null, null, null, null, BottomConviction.UNKNOWN,
                        null, ReversalState.UNKNOWN, null, TechnicalFlowState.UNKNOWN,
                        null, null, null, null, null, ThesisState.UNKNOWN, null, null),
                List.of(),
                2,
                65,
                List.of("핵심 밸류 데이터 없음", "섹터 데이터 없음")
        );

        var decision = policy.evaluate(sparse);

        assertEquals(CompanyInvestmentAction.HOLD, decision.action());
        assertEquals(CompanyOpportunityType.INSUFFICIENT_EVIDENCE, decision.opportunityType());
        assertNotEquals(CompanyInvestmentAction.STRONG_BUY, decision.action());
    }

    @Test
    void staleQuoteCannotAuthorizeANewBuyEvenWhenAllHistoricalScoresAreStrong() {
        var base = strongEvidence(List.of());
        var stale = withTiming(base, timingWithAges(base.timing(), 8, 45));

        var decision = policy.evaluate(stale);

        assertEquals(CompanyInvestmentAction.HOLD, decision.action());
        assertTrue(decision.dataQuality().confidence() < policy.evaluate(base).dataQuality().confidence());
    }

    @Test
    void oldFundamentalsCapConvictionAndExpiredFundamentalsForbidAveragingDown() {
        var base = strongEvidence(List.of());
        var aging = policy.evaluate(withTiming(base, timingWithAges(base.timing(), 1, 201)));
        var expired = policy.evaluate(withTiming(base, timingWithAges(base.timing(), 1, 401)));

        assertNotEquals(CompanyInvestmentAction.STRONG_BUY, aging.action());
        assertEquals(CompanyInvestmentAction.HOLD, expired.action());
        assertEquals(
                CompanyInvestmentDecision.ScaleInEligibilityState.INELIGIBLE,
                expired.scaleInEligibility().state());
        assertTrue(expired.scaleInEligibility().blockers().stream()
                .anyMatch(value -> value.contains("400일")));
    }

    @Test
    void missingObservationDatesCannotAuthorizeANewBuy() {
        var base = strongEvidence(List.of());
        var missingQuoteDate = policy.evaluate(withTiming(
                base, timingWithAges(base.timing(), null, 45)));
        var missingFundamentalsDate = policy.evaluate(withTiming(
                base, timingWithAges(base.timing(), 1, null)));

        assertEquals(CompanyInvestmentAction.HOLD, missingQuoteDate.action());
        assertEquals(CompanyInvestmentAction.HOLD, missingFundamentalsDate.action());
        assertEquals(0, missingQuoteDate.entryStrategy().initialEntryPctOfTarget());
        assertEquals(0, missingFundamentalsDate.entryStrategy().initialEntryPctOfTarget());
        assertEquals(
                CompanyInvestmentDecision.ScaleInEligibilityState.INELIGIBLE,
                missingQuoteDate.scaleInEligibility().state());
        assertEquals(
                CompanyInvestmentDecision.ScaleInEligibilityState.INELIGIBLE,
                missingFundamentalsDate.scaleInEligibility().state());
    }

    @Test
    void currentQuoteWithoutCurrentPriceStructureCannotAuthorizeANewBuy() {
        var base = strongEvidence(List.of());
        var unavailableTiming = new TimingEvidence(
                null, null, null, null, null, BottomConviction.UNKNOWN,
                null, ReversalState.UNKNOWN, null, TechnicalFlowState.UNKNOWN,
                null, PriceTrendState.UNKNOWN, PriceReversalStage.UNKNOWN,
                PriceRecoveryStage.UNKNOWN, PriceLocationState.UNKNOWN,
                MovingAverageState.UNKNOWN, null, false, false, false,
                FibonacciSwingDirection.UNKNOWN, FibonacciZoneState.UNKNOWN,
                null, null, false, false,
                null, null, null, null, null, ThesisState.INTACT, 1, 45
        );

        var decision = policy.evaluate(withTiming(base, unavailableTiming));

        assertEquals(CompanyInvestmentAction.HOLD, decision.action());
        assertEquals(0, decision.entryStrategy().initialEntryPctOfTarget());
    }

    private static CompanyInvestmentEvidence strongEvidence(List<HistoricalValidation> validations) {
        return new CompanyInvestmentEvidence(
                "TEST",
                new ScoreEvidence(84, 86, 88, 78, 86, 82, 24, 81),
                new FundamentalEvidence(
                        24.0, 28.0, 3.5, 22.0, 21.0, 26.0, -0.2, 2.2,
                        -1.0, -0.4, 4.0, -2.0, 86, 82, 78, 9,
                        EvidenceStrength.STRONG, EvidenceStrength.STRONG, EvidenceStrength.STRONG),
                new ValuationEvidence(
                        4.2, 19.0, -12.0, -15.0,
                        ValuationRangePosition.UNDERVALUED,
                        ValuationRelativePosition.DISCOUNT,
                        RiskBand.LOW, RiskBand.MODERATE, RiskBand.MODERATE),
                new CatalystEvidence(
                        18.0, 3.0, 6.0, 8.0, -0.05, -0.08, -0.1,
                        GuidanceDirection.RAISED, 78, NarrativeStage.EARLY, NarrativeTrend.HEATING),
                new SectorEvidence(
                        76, 78, 74, 38, 68, 76, 79, 78, 84, 76, 72,
                        SectorStance.FAVORED, SectorRotationState.IMPROVING, MarketBias.BUY, "1_3m"),
                new TimingEvidence(
                        76, 74, 82, 28, 79, BottomConviction.CONVICTION,
                        84, ReversalState.STRONG, 78, TechnicalFlowState.ACCUMULATION,
                        84, PriceTrendState.UPTREND, PriceReversalStage.INTACT,
                        PriceRecoveryStage.RETEST_HELD, PriceLocationState.SUPPORT_ZONE,
                        MovingAverageState.BULLISH_ALIGNED, 43.0, false, false, false,
                        78, 26, 80, 78, 76, ThesisState.INTACT, 1, 45),
                validations,
                63,
                65,
                List.of()
        );
    }

    private static CompanyInvestmentEvidence withTiming(
            CompanyInvestmentEvidence base,
            TimingEvidence timing
    ) {
        return new CompanyInvestmentEvidence(
                base.ticker(),
                base.scores(),
                base.fundamentals(),
                base.valuation(),
                base.catalyst(),
                base.sector(),
                timing,
                base.historicalValidations(),
                73,
                75,
                base.dataWarnings()
        );
    }

    private static TimingEvidence timingWithAges(
            TimingEvidence source,
            Integer quoteAgeDays,
            Integer fundamentalsAgeDays
    ) {
        return new TimingEvidence(
                source.bottomScore(), source.priceBottomScore(), source.volumeConfirmationScore(),
                source.failureRiskScore(), source.confirmedBottomScore(), source.bottomConviction(),
                source.reversalScore(), source.reversalState(), source.technicalConfirmationScore(),
                source.technicalFlowState(), source.priceStructureScore(), source.priceTrendState(),
                source.priceReversalStage(), source.priceRecoveryStage(), source.priceLocationState(),
                source.movingAverageState(), source.rsi14(), source.oversoldConfluence(),
                source.stopHuntReclaim(), source.volumeBreakout(), source.fibonacciSwingDirection(),
                source.fibonacciZoneState(), source.fibonacciNearestRatio(),
                source.fibonacciConfluenceScore(), source.fibonacciWeeklyConfluence(),
                source.fibonacciSupportConfluence(), source.correctionScore(),
                source.trendBreakRiskScore(), source.shortTermScore(), source.swingTermScore(),
                source.longTermScore(), source.thesisState(), quoteAgeDays, fundamentalsAgeDays);
    }

    private static HistoricalValidation validation(
            String horizon,
            int days,
            int signals,
            double positive,
            double target,
            double targetHit,
            double average,
            double drawdown
    ) {
        return new HistoricalValidation(
                horizon, days, signals, positive, target, targetHit, average, drawdown);
    }
}
