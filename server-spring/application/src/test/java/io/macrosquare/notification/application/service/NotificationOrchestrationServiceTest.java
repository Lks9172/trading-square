package io.macrosquare.notification.application.service;

import io.macrosquare.notification.application.model.MarketNotificationSnapshot;
import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.notification.application.port.out.LoadInvestmentCandidatesPort;
import io.macrosquare.notification.application.port.out.LoadMarketNotificationPort;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import io.macrosquare.notification.domain.InvestmentCandidatePolicy;
import io.macrosquare.notification.domain.TechnicalTimingEvidence;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationOrchestrationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-21T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void refreshesTheCompleteUniverseBeforeFilteringAndAdmitsANewlyQualifiedCompany() {
        var capturedBelowThreshold = candidate(
                BottomCandidateState.UNMET, 58, 69, 69, "HOLD", "OFF");
        var refreshed = candidateWithTiming(
                BottomCandidateState.CONVICTION, 82, 76, 81, "STRONG BUY", "STRONG");
        var refreshCalls = new AtomicInteger();
        var repository = new InMemoryStateRepository();
        var service = new NotificationOrchestrationService(
                candidates(capturedBelowThreshold),
                market(),
                repository,
                value -> {
                    refreshCalls.incrementAndGet();
                    return refreshed;
                },
                new InvestmentCandidatePolicy(),
                Runnable::run,
                CLOCK
        );

        var admitted = service.scanCandidates("test-scan");

        assertEquals(1, refreshCalls.get());
        assertEquals(1, admitted);
        assertEquals(java.util.Set.of("company:TEST"), repository.state.candidateKeys());
        assertEquals(1, repository.outbox.size());
        assertTrue(repository.outbox.getFirst().text().contains("신규 기업 진입 신호"));
        assertTrue(repository.outbox.getFirst().text().contains(
                "회사: 총점≥70 · B≥70 · 찐바닥 후보 이상 · 반전 ON 이상 (실행 액션 무관)"));
        assertTrue(repository.outbox.getFirst().text().contains("TEST — Test Company"));
        assertTrue(repository.outbox.getFirst().text().contains("B점수: 81 / 총점: 76"));
        assertTrue(repository.outbox.getFirst().text().contains("반전 확인 신호: ON(강함)"));
        assertTrue(repository.outbox.getFirst().text().contains(
                "MACD 일봉: 2026-07-21 기준 · 상방 골든크로스(2일 전) · 시그널 위 · 양(확대)"
                        + " · 상승 다이버전스 ON(1일 전)"));
        assertTrue(repository.outbox.getFirst().text().contains(
                "MACD 주봉: 2026-07-21 기준 · 하방 데드크로스(1주 전) · 시그널 아래"
                        + " · 음(축소) · 다이버전스 없음 · 이번 주 진행 중"));
        assertTrue(repository.outbox.getFirst().text().contains(
                "MACD는 진입 타이밍 보조이며 단독 매수·매도 신호가 아님"));
        assertEquals(List.of(refreshed), repository.state.candidates());
    }

    @Test
    void admitsACandidateBottomWhenTheIndependentReversalIsConfirmed() {
        var candidateBottom = candidateWithTiming(
                BottomCandidateState.CANDIDATE, 74, 75, 78, "HOLD", "ON");
        var repository = new InMemoryStateRepository();
        var service = new NotificationOrchestrationService(
                candidates(candidateBottom), market(), repository, value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        assertEquals(1, service.scanCandidates("candidate-bottom-policy"));
        assertEquals(Set.of(candidateBottom.key()), repository.state.candidateKeys());
        assertTrue(repository.outbox.getFirst().text().contains("상태: 후보 (74)"));
        assertTrue(repository.outbox.getFirst().text().contains("반전 확인 신호: ON(보통)"));
    }

    @Test
    void notifiesWhenAQualifiedCompanyReversalStrengthensFromOnToStrongRegardlessOfAction() {
        var previous = candidate(
                BottomCandidateState.CONVICTION, 82, 72, 73, "REDUCE", "ON");
        var current = candidateWithTiming(
                BottomCandidateState.CONVICTION, 84, 72, 73, "SELL", "STRONG");
        var repository = new InMemoryStateRepository(new NotificationState(
                Set.of(previous.key()), "market", Instant.parse("2026-07-20T00:00:00Z"),
                List.of(previous)));
        var service = new NotificationOrchestrationService(
                candidates(current), market(), repository, value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        assertEquals(1, service.scanCandidates("reversal-upgrade"));
        assertEquals(1, repository.outbox.size());
        assertTrue(repository.outbox.getFirst().text().contains("기업 신호 강화"));
        assertTrue(repository.outbox.getFirst().text().contains("반전확인: ON → STRONG"));
        assertTrue(repository.outbox.getFirst().text().contains("MACD 일봉:"));
        assertTrue(repository.outbox.getFirst().text().contains("참고 실행 액션: SELL"));
    }

    @Test
    void notifiesOnceWhenCompanyOrBuyScoreCrossesFivePointBandsAtOrAboveSeventy() {
        var previous = candidate(
                BottomCandidateState.CONVICTION, 82, 74, 79, "HOLD", "STRONG");
        var current = candidate(
                BottomCandidateState.CONVICTION, 82, 75, 80, "HOLD", "STRONG");
        var repository = new InMemoryStateRepository(new NotificationState(
                Set.of(previous.key()), "market", Instant.parse("2026-07-20T00:00:00Z"),
                List.of(previous)));
        var service = new NotificationOrchestrationService(
                candidates(current), market(), repository, value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        assertEquals(1, service.scanCandidates("score-upgrade"));
        assertEquals(0, service.scanCandidates("same-score"));
        assertEquals(1, repository.outbox.size());
        var text = repository.outbox.getFirst().text();
        assertTrue(text.contains("기업점수 구간: 70 → 75 (현재 75)"));
        assertTrue(text.contains("B점수 구간: 75 → 80 (현재 80)"));
    }

    @Test
    void startupUsesTheLatestPersistedCandidateSnapshotInsteadOfTheHandoffSeed() {
        var persisted = candidate(
                BottomCandidateState.CONVICTION, 84, 78, 82, "STRONG BUY", "STRONG");
        var staleSeed = new InvestmentCandidate(
                CandidateKind.COMPANY, "OLD", "Old Seed", "Legacy",
                BottomCandidateState.CANDIDATE, 70, 70, 70, "BUY",
                LocalDate.parse("2026-06-01"), "EARLY", 65, List.of("old"));
        var repository = new InMemoryStateRepository(new NotificationState(
                Set.of(persisted.key()), "old-fingerprint", Instant.parse("2026-07-20T00:00:00Z"),
                List.of(persisted)));
        var service = new NotificationOrchestrationService(
                startupAndScanCandidates(staleSeed), market(), repository,
                value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        service.dispatchStartup();

        assertEquals(1, repository.outbox.size());
        assertTrue(repository.outbox.getFirst().text().contains("TEST — Test Company"));
        assertTrue(!repository.outbox.getFirst().text().contains("OLD — Old Seed"));
        assertEquals(List.of(persisted), repository.state.candidates());
    }

    @Test
    void startupRemovesAPersistedCandidateThatFailsCurrentRevalidation() {
        var persisted = candidate(
                BottomCandidateState.CONVICTION, 84, 78, 82, "STRONG BUY", "STRONG");
        var invalidNow = candidate(
                BottomCandidateState.UNMET, 0, 0, 0, "HOLD", "OFF");
        var repository = new InMemoryStateRepository(new NotificationState(
                Set.of(persisted.key()), "old-fingerprint", Instant.parse("2026-07-20T00:00:00Z"),
                List.of(persisted)));
        var refreshCalls = new AtomicInteger();
        var service = new NotificationOrchestrationService(
                startupAndScanCandidates(persisted), market(), repository,
                value -> {
                    refreshCalls.incrementAndGet();
                    return invalidNow;
                },
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        service.dispatchStartup();

        assertEquals(1, refreshCalls.get());
        assertEquals(Set.of(), repository.state.candidateKeys());
        assertEquals(List.of(), repository.state.candidates());
        assertTrue(repository.outbox.getFirst().text().contains("현재 진입 신호 충족 종목: 없음"));
    }

    @Test
    void startupPreservesAnAuthoritativeEmptyCandidateSnapshotInsteadOfResurrectingAStaleSeed() {
        var staleSeed = candidate(
                BottomCandidateState.CONVICTION, 84, 78, 82, "STRONG BUY", "STRONG");
        var repository = new InMemoryStateRepository(new NotificationState(
                Set.of(), "old-fingerprint", Instant.parse("2026-07-20T00:00:00Z"), List.of()));
        var service = new NotificationOrchestrationService(
                startupAndScanCandidates(staleSeed), market(), repository,
                value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        service.dispatchStartup();

        assertEquals(1, repository.outbox.size());
        assertTrue(repository.outbox.getFirst().text().contains("현재 진입 신호 충족 종목: 없음"));
        assertTrue(!repository.outbox.getFirst().text().contains("TEST — Test Company"));
        assertEquals(Set.of(), repository.state.candidateKeys());
        assertEquals(List.of(), repository.state.candidates());
    }

    @Test
    void startupUsesTheHandoffSeedOnlyForAStateThatHasNeverBeenInitialized() {
        var seed = candidate(
                BottomCandidateState.CONVICTION, 84, 78, 82, "STRONG BUY", "STRONG");
        var repository = new InMemoryStateRepository(NotificationState.empty());
        var service = new NotificationOrchestrationService(
                startupAndScanCandidates(seed), market(), repository,
                value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        service.dispatchStartup();

        assertEquals(1, repository.outbox.size());
        assertTrue(repository.outbox.getFirst().text().contains("TEST — Test Company"));
        assertEquals(Set.of(seed.key()), repository.state.candidateKeys());
        assertEquals(List.of(seed), repository.state.candidates());
    }

    @Test
    void candidateStateAndOutboxAreCommittedTogetherAndDoNotRequeueWithoutAStateTransition() {
        var qualified = candidate(BottomCandidateState.CONVICTION, 82, 76, 81, "STRONG BUY", "STRONG");
        var repository = new InMemoryStateRepository();
        var service = new NotificationOrchestrationService(
                candidates(qualified), market(), repository,
                value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        assertEquals(1, service.scanCandidates("first"));
        assertEquals(0, service.scanCandidates("again"));
        assertEquals(Set.of(qualified.key()), repository.state.candidateKeys());
        assertEquals(1, repository.outbox.size());
    }

    @Test
    void refreshFailureCannotResurrectAStaleQualifyingCandidate() {
        var staleQualified = candidate(
                BottomCandidateState.CONVICTION, 90, 90, 90, "STRONG BUY", "STRONG");
        var repository = new InMemoryStateRepository();
        var service = new NotificationOrchestrationService(
                candidates(staleQualified), market(), repository,
                value -> { throw new IllegalStateException("current source unavailable"); },
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        assertEquals(0, service.scanCandidates("refresh-failure"));
        assertEquals(Set.of(), repository.state.candidateKeys());
        assertEquals(List.of(), repository.state.candidates());
        assertEquals(0, repository.outbox.size());
    }

    @Test
    void refreshSubmissionFailureCannotResurrectAStaleQualifyingCandidate() {
        var staleQualified = candidate(
                BottomCandidateState.CONVICTION, 90, 90, 90, "STRONG BUY", "STRONG");
        var repository = new InMemoryStateRepository();
        var service = new NotificationOrchestrationService(
                candidates(staleQualified), market(), repository,
                value -> value,
                new InvestmentCandidatePolicy(),
                command -> { throw new java.util.concurrent.RejectedExecutionException("saturated"); },
                CLOCK
        );

        assertEquals(0, service.scanCandidates("refresh-submit-failure"));
        assertEquals(Set.of(), repository.state.candidateKeys());
        assertEquals(List.of(), repository.state.candidates());
        assertEquals(0, repository.outbox.size());
    }

    @Test
    void marketChangeQueuesOnceAndAdvancesTheFingerprintAtomically() {
        var previous = new NotificationState(Set.of(), "previous", Instant.EPOCH, List.of());
        var repository = new InMemoryStateRepository(previous);
        var service = new NotificationOrchestrationService(
                candidates(candidate(BottomCandidateState.UNMET, 40, 40, 40, "HOLD", "OFF")),
                market(), repository, value -> value,
                new InvestmentCandidatePolicy(), Runnable::run, CLOCK
        );

        assertTrue(service.checkMarketChanges("first"));
        assertTrue(!service.checkMarketChanges("same"));
        assertEquals(market().loadCurrent().fingerprint(), repository.state.marketFingerprint());
        assertEquals(1, repository.outbox.size());
    }

    private static LoadInvestmentCandidatesPort candidates(InvestmentCandidate candidate) {
        return new LoadInvestmentCandidatesPort() {
            @Override
            public List<InvestmentCandidate> loadStartupCandidates() {
                return List.of();
            }

            @Override
            public List<InvestmentCandidate> loadScanUniverse() {
                return List.of(candidate);
            }
        };
    }

    private static LoadInvestmentCandidatesPort startupAndScanCandidates(InvestmentCandidate candidate) {
        return new LoadInvestmentCandidatesPort() {
            @Override
            public List<InvestmentCandidate> loadStartupCandidates() {
                return List.of(candidate);
            }

            @Override
            public List<InvestmentCandidate> loadScanUniverse() {
                return List.of(candidate);
            }
        };
    }

    private static LoadMarketNotificationPort market() {
        var snapshot = new MarketNotificationSnapshot(
                "2026-07-21T03:00:00Z", "NEUTRAL", 68,
                List.of(new MarketNotificationSnapshot.Signal(
                        "NASDAQ", "BUY", 4, 5, 100, "⚠ 액션 상한: 추격 제한")),
                Map.of("nasdaq", 60, "cash", 40), false, List.of("NASDAQ 반전신호: ON"));
        return new LoadMarketNotificationPort() {
            @Override
            public MarketNotificationSnapshot loadCurrent() {
                return snapshot;
            }

            @Override
            public String loadWeeklyReportText() {
                return "weekly";
            }
        };
    }

    private static InvestmentCandidate candidate(
            BottomCandidateState bottomState,
            int bottomScore,
            int totalScore,
            int buyScore,
            String action,
            String reversalStatus
    ) {
        return new InvestmentCandidate(
                CandidateKind.COMPANY, "TEST", "Test Company", "Technology",
                bottomState, bottomScore, totalScore, buyScore, action,
                LocalDate.parse("2026-07-18"), reversalStatus, bottomScore, List.of("test reason")
        );
    }

    private static InvestmentCandidate candidateWithTiming(
            BottomCandidateState bottomState,
            int bottomScore,
            int totalScore,
            int buyScore,
            String action,
            String reversalStatus
    ) {
        return new InvestmentCandidate(
                CandidateKind.COMPANY, "TEST", "Test Company", "Technology",
                bottomState, bottomScore, totalScore, buyScore, action,
                LocalDate.parse("2026-07-18"), reversalStatus, bottomScore, List.of("test reason"),
                new TechnicalTimingEvidence(
                        new TechnicalTimingEvidence.Timeframe(
                                LocalDate.parse("2026-07-21"),
                                TechnicalTimingEvidence.Position.ABOVE_SIGNAL,
                                TechnicalTimingEvidence.Cross.BULLISH_CROSS,
                                LocalDate.parse("2026-07-18"), 2,
                                TechnicalTimingEvidence.Histogram.EXPANDING_POSITIVE,
                                TechnicalTimingEvidence.Divergence.BULLISH,
                                LocalDate.parse("2026-07-20"), 1, true),
                        new TechnicalTimingEvidence.Timeframe(
                                LocalDate.parse("2026-07-21"),
                                TechnicalTimingEvidence.Position.BELOW_SIGNAL,
                                TechnicalTimingEvidence.Cross.BEARISH_CROSS,
                                LocalDate.parse("2026-07-14"), 1,
                                TechnicalTimingEvidence.Histogram.CONTRACTING_NEGATIVE,
                                TechnicalTimingEvidence.Divergence.NONE,
                                null, null, false),
                        true
                )
        );
    }

    private static final class InMemoryStateRepository implements NotificationStateRepository {
        private NotificationState state;
        private final List<OutboundNotification> outbox = new ArrayList<>();

        private InMemoryStateRepository() {
            this(NotificationState.empty());
        }

        private InMemoryStateRepository(NotificationState state) {
            this.state = state;
        }

        @Override
        public NotificationState load() {
            return state;
        }

        @Override
        public void save(NotificationState state) {
            this.state = state;
        }

        @Override
        public synchronized <R> R updateAtomically(
                Function<NotificationState, NotificationStateChange<R>> transition
        ) {
            var change = transition.apply(state);
            state = change.state();
            for (var message : change.notifications()) {
                if (outbox.stream().noneMatch(existing ->
                        existing.idempotencyKey().equals(message.idempotencyKey()))) {
                    outbox.add(message);
                }
            }
            return change.result();
        }
    }
}
