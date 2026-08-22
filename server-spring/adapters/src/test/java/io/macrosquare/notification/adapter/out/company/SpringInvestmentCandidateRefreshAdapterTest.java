package io.macrosquare.notification.adapter.out.company;

import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot;
import io.macrosquare.company.application.model.CompanyMacdTimingSnapshot;
import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.port.in.CompanyPriceSignalParityReport;
import io.macrosquare.company.application.port.in.CompanyResearchParityReport;
import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.bottom.BottomActionBias;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.DeepBottomState;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.ReversalConfirmationStatus;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringInvestmentCandidateRefreshAdapterTest {

    @Test
    void usesCurrentPersistedNotificationEvidenceWithoutRepeatingChartOrResearchEvaluation() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        var summaries = mock(CompanyResearchSummaryRepository.class);
        var summary = mock(CompanyResearchSummarySnapshot.class);
        when(summary.notificationEvidenceCurrentAt(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(summary.totalScore()).thenReturn(79);
        when(summary.buyScore()).thenReturn(83);
        when(summary.executionAction()).thenReturn("STRONG BUY");
        when(summary.confirmedBottomState()).thenReturn("CONVICTION");
        when(summary.confirmedBottomScore()).thenReturn(84);
        when(summary.confirmedBottomSignalDate()).thenReturn(LocalDate.parse("2026-07-19"));
        when(summary.reversalStatus()).thenReturn("STRONG");
        when(summary.reversalScore()).thenReturn(88);
        when(summary.priceSignalReasons()).thenReturn(List.of("persisted current price evidence"));
        when(summary.macdTiming()).thenReturn(persistedMacdTiming());
        when(summaries.find("TEST")).thenReturn(Optional.of(summary));
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals, summaries);

        var result = adapter.refresh(company());

        assertEquals(79, result.totalScore());
        assertEquals(83, result.buyScore());
        assertEquals("STRONG BUY", result.action());
        assertEquals(BottomCandidateState.CONVICTION, result.bottomState());
        assertEquals("STRONG", result.reversalStatus());
        assertEquals(List.of("persisted current price evidence"), result.reasons());
        assertEquals("BULLISH_CROSS", result.technicalTiming().daily().latestCross().name());
        verify(research, never()).evaluate("TEST");
        verify(priceSignals, never()).evaluate("TEST");
    }

    @Test
    void rejectsPersistedScoresWhoseFilingFreshnessIsNotComparable() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        var summaries = mock(CompanyResearchSummaryRepository.class);
        var summary = mock(CompanyResearchSummarySnapshot.class);
        when(summary.scoreComparableAt(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        when(summaries.find("TEST")).thenReturn(Optional.of(summary));
        stubPriceSignals(priceSignals, DeepBottomState.CONVICTION, 84,
                ReversalConfirmationStatus.STRONG, 88);
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals, summaries);

        var result = adapter.refresh(company());

        assertEquals(0, result.totalScore());
        assertEquals(0, result.buyScore());
        assertEquals("HOLD", result.action());
        verify(research).evaluate("TEST");
    }

    @Test
    void refreshesCoreScoresAndPriceSignalsFromIndependentSpringUseCases() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        stubResearch(research, 77, 82);
        stubPriceSignals(priceSignals, DeepBottomState.CONVICTION, 84, ReversalConfirmationStatus.STRONG, 88);
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals);

        var result = adapter.refresh(company());

        assertEquals(77, result.totalScore());
        assertEquals(82, result.buyScore());
        assertEquals("HOLD", result.action());
        assertEquals(BottomCandidateState.CONVICTION, result.bottomState());
        assertEquals(84, result.bottomScore());
        assertEquals("STRONG", result.reversalStatus());
        assertEquals(88, result.reversalScore());
        assertEquals(List.of("direct bottom evidence"), result.reasons());
    }

    @Test
    void suppressesLastValidScoresWhenCurrentCoreRefreshFails() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        when(research.evaluate("TEST")).thenThrow(new IllegalStateException("SEC unavailable"));
        stubPriceSignals(priceSignals, DeepBottomState.CANDIDATE, 73, ReversalConfirmationStatus.ON, 76);
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals);

        var result = adapter.refresh(company());

        assertEquals(0, result.totalScore());
        assertEquals(0, result.buyScore());
        assertEquals("HOLD", result.action());
        assertEquals(BottomCandidateState.CANDIDATE, result.bottomState());
        assertEquals(73, result.bottomScore());
        assertEquals("ON", result.reversalStatus());
    }

    @Test
    void suppressesAResearchResultWhoseCurrentValuationIsNotComparable() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        var report = mock(CompanyResearchParityReport.class);
        when(report.scoreComparable()).thenReturn(false);
        when(research.evaluate("TEST")).thenReturn(report);
        stubPriceSignals(priceSignals, DeepBottomState.CONVICTION, 90,
                ReversalConfirmationStatus.STRONG, 90);
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals);

        var result = adapter.refresh(company());

        assertEquals(0, result.totalScore());
        assertEquals(0, result.buyScore());
        assertEquals("HOLD", result.action());
    }

    @Test
    void suppressesPersistedBottomAndReversalWhenCurrentPriceBasisValidationFails() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        stubResearch(research, 80, 82);
        when(priceSignals.evaluate("TEST")).thenThrow(new IllegalStateException("unadjusted split"));
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals);

        var result = adapter.refresh(company());

        assertEquals(BottomCandidateState.UNMET, result.bottomState());
        assertEquals("OFF", result.reversalStatus());
        assertEquals(null, result.bottomScore());
        assertEquals(null, result.reversalScore());
    }

    @Test
    void keepsBottomEvidenceVisibleWhileAddingReadablePriceStructureEvidence() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        var summaries = mock(CompanyResearchSummaryRepository.class);
        var summary = mock(CompanyResearchSummarySnapshot.class);
        when(summary.scoreComparableAt(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(summary.totalScore()).thenReturn(77);
        when(summary.buyScore()).thenReturn(82);
        when(summary.executionAction()).thenReturn("STRONG BUY");
        when(summaries.find("TEST")).thenReturn(Optional.of(summary));
        stubPriceSignals(priceSignals, DeepBottomState.CONVICTION, 84, ReversalConfirmationStatus.STRONG, 88);
        var report = priceSignals.evaluate("TEST");
        when(report.spring().priceStructure()).thenReturn(new PriceStructureAnalysis(
                67,
                PriceStructureAnalysis.TrendState.TRANSITION,
                PriceStructureAnalysis.BearishReversalStage.STRUCTURAL_CRACK,
                PriceStructureAnalysis.RecoveryStage.REBOUND,
                PriceStructureAnalysis.PriceLocation.SUPPORT_ZONE,
                PriceStructureAnalysis.MovingAverageState.CONVERGED,
                34.0,
                100.0, 101.0, 102.0, 103.0, 3.0,
                95.0, 105.0, 115.0, 25.0, 7.0,
                new PriceStructureAnalysis.PriceZone(96.0, 99.0, 3, 75, true),
                new PriceStructureAnalysis.PriceZone(108.0, 111.0, 2, 60, false),
                18, 7.0,
                true, true, true,
                FibonacciRetracementAnalysis.unavailable(),
                List.of("지지 구간에서 회복 중입니다."),
                List.of("2단계 구조 균열이라 확대 전 고점 회복이 필요합니다."),
                "test methodology",
                List.of()
        ));
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals, summaries);

        var result = adapter.refresh(company());

        assertEquals("HOLD", result.action());
        assertEquals(
                "가격구조 67/100(확률 아님) · 전환 · 훼손 2단계 · 회복 반등 · 위치 지지"
                        + " · 거래량 돌파 · 스톱헌트 회복 · RSI 다중확인",
                result.reasons().get(0)
        );
        assertTrue(result.reasons().get(1).startsWith("실행 제한:"));
        assertEquals("direct bottom evidence", result.reasons().get(2));
        assertEquals("2단계 구조 균열이라 확대 전 고점 회복이 필요합니다.", result.reasons().get(3));
        assertEquals("지지 구간에서 회복 중입니다.", result.reasons().get(4));
    }

    @Test
    void addsTheNearestFibonacciConfluenceWithoutPresentingItAsAStandaloneSignal() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        stubResearch(research, 77, 82);
        stubPriceSignals(priceSignals, DeepBottomState.CONVICTION, 84, ReversalConfirmationStatus.STRONG, 88);
        var report = priceSignals.evaluate("TEST");
        var fibonacci = new FibonacciRetracementAnalysis(
                FibonacciRetracementAnalysis.SwingDirection.UP_SWING,
                LocalDate.parse("2026-05-01"),
                LocalDate.parse("2026-07-01"),
                80.0,
                120.0,
                50.0,
                95.0,
                0.625,
                List.of(
                        new FibonacciRetracementAnalysis.FibonacciLevel(.236, 110.56, "0.236"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.382, 104.72, "0.382"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.5, 100.0, "0.500"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.618, 95.28, "0.618"),
                        new FibonacciRetracementAnalysis.FibonacciLevel(.786, 88.56, "0.786")
                ),
                .618,
                95.28,
                .29,
                FibonacciRetracementAnalysis.TimeframeReliability.WEEKLY_CONFIRMED,
                true,
                true,
                false,
                88,
                FibonacciRetracementAnalysis.ZoneState.DEEP_RETRACEMENT,
                "0.618 되돌림과 주봉 지지가 겹칩니다.",
                List.of(),
                "test methodology"
        );
        when(report.spring().priceStructure()).thenReturn(new PriceStructureAnalysis(
                72,
                PriceStructureAnalysis.TrendState.TRANSITION,
                PriceStructureAnalysis.BearishReversalStage.STRUCTURAL_CRACK,
                PriceStructureAnalysis.RecoveryStage.REBOUND,
                PriceStructureAnalysis.PriceLocation.SUPPORT_ZONE,
                PriceStructureAnalysis.MovingAverageState.CONVERGED,
                34.0,
                100.0, 101.0, 102.0, 103.0, 3.0,
                95.0, 105.0, 115.0, 25.0, 7.0,
                new PriceStructureAnalysis.PriceZone(96.0, 99.0, 3, 75, true),
                new PriceStructureAnalysis.PriceZone(108.0, 111.0, 2, 60, false),
                18, 7.0,
                true, true, true,
                fibonacci,
                List.of("지지 구간에서 회복 중입니다."),
                List.of(),
                "test methodology",
                List.of()
        ));
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals);

        var result = adapter.refresh(company());

        assertTrue(result.reasons().getFirst().contains("피보 0.618/합치88"));
        assertTrue(result.reasons().getFirst().contains("확률 아님"));
    }

    @Test
    void leavesCryptoCandidatesUntouchedBecauseCryptoRefreshHasItsOwnBoundedContext() {
        var research = mock(EvaluateCompanyResearchParityUseCase.class);
        var priceSignals = mock(EvaluateCompanyPriceSignalParityUseCase.class);
        var adapter = new SpringInvestmentCandidateRefreshAdapter(research, priceSignals);
        var crypto = new InvestmentCandidate(
                CandidateKind.CRYPTO, "BTC", "Bitcoin", "Store of value",
                BottomCandidateState.CANDIDATE, 75, 78, 81, "STRONG BUY",
                LocalDate.parse("2026-07-18"), "STRONG", 83, List.of("crypto evidence"));

        assertSame(crypto, adapter.refresh(crypto));
        verify(research, never()).evaluate("BTC");
        verify(priceSignals, never()).evaluate("BTC");
    }

    private static void stubResearch(
            EvaluateCompanyResearchParityUseCase useCase,
            int totalScore,
            int buyScore
    ) {
        var report = mock(CompanyResearchParityReport.class);
        var score = mock(CompanyScore.class);
        var buy = mock(CompanyBuyScore.class);
        when(score.totalScore()).thenReturn(totalScore);
        when(buy.buyScore()).thenReturn(buyScore);
        when(report.springScore()).thenReturn(score);
        when(report.springBuyScore()).thenReturn(buy);
        when(report.scoreComparable()).thenReturn(true);
        when(useCase.evaluate("TEST")).thenReturn(report);
    }

    private static void stubPriceSignals(
            EvaluateCompanyPriceSignalParityUseCase useCase,
            DeepBottomState state,
            int bottomScore,
            ReversalConfirmationStatus reversalStatus,
            int reversalScore
    ) {
        var report = mock(CompanyPriceSignalParityReport.class);
        var snapshot = mock(CompanyPriceSignalSnapshot.class);
        var date = LocalDate.parse("2026-07-19");
        var bottom = new DeepBottomSignal(
                bottomScore, state, BottomActionBias.SCALE_IN_BUY, date, 2,
                "direct", 2.1, .8, -22.0, -4.0, -6.0,
                List.of("direct bottom evidence"), List.of());
        var reversal = new ReversalConfirmation(
                reversalStatus, reversalScore, date, "confirmed", List.of("reversal evidence"), List.of());
        when(snapshot.confirmedBottom()).thenReturn(bottom);
        when(snapshot.reversalConfirmation()).thenReturn(reversal);
        when(report.spring()).thenReturn(snapshot);
        when(useCase.evaluate("TEST")).thenReturn(report);
    }

    private static InvestmentCandidate company() {
        return new InvestmentCandidate(
                CandidateKind.COMPANY, "TEST", "Test Company", "Technology",
                BottomCandidateState.CANDIDATE, 70, 71, 72, "BUY",
                LocalDate.parse("2026-07-17"), "EARLY", 68, List.of("persisted evidence"));
    }

    private static CompanyMacdTimingSnapshot persistedMacdTiming() {
        return new CompanyMacdTimingSnapshot(
                new CompanyMacdTimingSnapshot.Timeframe(
                        LocalDate.parse("2026-07-20"), "ABOVE_SIGNAL", "BULLISH_CROSS",
                        LocalDate.parse("2026-07-18"), 2, "EXPANDING_POSITIVE", "BULLISH",
                        LocalDate.parse("2026-07-19"), 1, true),
                new CompanyMacdTimingSnapshot.Timeframe(
                        LocalDate.parse("2026-07-20"), "ABOVE_SIGNAL", "BULLISH_CROSS",
                        LocalDate.parse("2026-07-18"), 1, "CONTRACTING_POSITIVE", "NONE",
                        null, null, false),
                true
        );
    }
}
