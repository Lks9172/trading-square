package io.macrosquare.notification.application.service;

import io.macrosquare.notification.application.model.MarketNotificationSnapshot;
import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.notification.application.port.in.NotificationOrchestrationUseCase;
import io.macrosquare.notification.application.port.out.LoadInvestmentCandidatesPort;
import io.macrosquare.notification.application.port.out.LoadMarketNotificationPort;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;
import io.macrosquare.notification.application.port.out.RefreshInvestmentCandidatePort;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import io.macrosquare.notification.domain.InvestmentCandidatePolicy;
import io.macrosquare.notification.domain.InvestmentCandidateStrengthening;
import io.macrosquare.notification.domain.TechnicalTimingEvidence;
import io.macrosquare.shared.application.port.out.OperationalEventSink;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

public final class NotificationOrchestrationService implements NotificationOrchestrationUseCase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("yyyy. M. d. a h:mm:ss");

    private final LoadInvestmentCandidatesPort candidates;
    private final LoadMarketNotificationPort market;
    private final NotificationStateRepository stateRepository;
    private final RefreshInvestmentCandidatePort candidateRefresher;
    private final InvestmentCandidatePolicy policy;
    private final Executor executor;
    private final Clock clock;
    private final OperationalEventSink operationalEvents;
    private final ReentrantLock stateLock = new ReentrantLock(true);

    public NotificationOrchestrationService(
            LoadInvestmentCandidatesPort candidates,
            LoadMarketNotificationPort market,
            NotificationStateRepository stateRepository,
            RefreshInvestmentCandidatePort candidateRefresher,
            InvestmentCandidatePolicy policy,
            Executor executor,
            Clock clock
    ) {
        this(candidates, market, stateRepository, candidateRefresher, policy, executor, clock,
                OperationalEventSink.noop());
    }

    public NotificationOrchestrationService(
            LoadInvestmentCandidatesPort candidates,
            LoadMarketNotificationPort market,
            NotificationStateRepository stateRepository,
            RefreshInvestmentCandidatePort candidateRefresher,
            InvestmentCandidatePolicy policy,
            Executor executor,
            Clock clock,
            OperationalEventSink operationalEvents
    ) {
        this.candidates = Objects.requireNonNull(candidates);
        this.market = Objects.requireNonNull(market);
        this.stateRepository = Objects.requireNonNull(stateRepository);
        this.candidateRefresher = Objects.requireNonNull(candidateRefresher);
        this.policy = Objects.requireNonNull(policy);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.operationalEvents = Objects.requireNonNull(operationalEvents);
    }

    @Override
    public boolean dispatchStartup() {
        var snapshot = market.loadCurrent();
        var startupCandidates = candidates.loadStartupCandidates();
        var now = clock.instant();
        stateLock.lock();
        try {
            var persisted = stateRepository.load();
            var initialized = !Instant.EPOCH.equals(persisted.updatedAt());
            var source = initialized ? persisted.candidates() : startupCandidates;
            // Startup stays snapshot-first and bounded (at most the persisted
            // qualifying set), but every displayed candidate is revalidated.
            // This prevents a pre-split/stale score from being repeated after
            // a restart before the full-universe scheduled scan runs.
            var refreshed = source.stream()
                    .map(this::refreshAsync)
                    .toList().stream().map(CompletableFuture::join).toList();
            var qualified = policy.qualified(refreshed, 12);
            return stateRepository.updateAtomically(previous -> {
                var keys = qualified.stream().map(InvestmentCandidate::key)
                        .collect(java.util.stream.Collectors.toSet());
                var next = new NotificationState(
                        keys, snapshot.fingerprint(), persisted.integrityFingerprint(), now, qualified);
                var message = OutboundNotification.create(
                        "startup",
                        now.toEpochMilli() + "|" + snapshot.fingerprint(),
                        startupMessage(snapshot, qualified),
                        now);
                return NotificationStateChange.withNotification(next, message, true);
            });
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public int scanCandidates(String trigger) {
        // Refresh the complete universe before eligibility filtering. Filtering the captured
        // scores first would permanently miss a company that has just crossed 70.
        var base = candidates.loadScanUniverse();
        var refreshed = base.stream().map(this::refreshAsync)
                .toList().stream().map(CompletableFuture::join).toList();
        // Persist every qualifying snapshot. A top-N state would lose the prior
        // score/reversal band of lower-ranked names and could not detect a later
        // 70→75→80 or ON→STRONG transition correctly.
        var qualified = policy.qualified(refreshed, refreshed.size());
        var marketSnapshot = market.loadCurrent();
        var now = clock.instant();
        stateLock.lock();
        try {
            return stateRepository.updateAtomically(previous -> {
                var currentKeys = qualified.stream().map(InvestmentCandidate::key)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                var added = qualified.stream()
                        .filter(value -> !previous.candidateKeys().contains(value.key()))
                        .toList();
                var previousByKey = new LinkedHashMap<String, InvestmentCandidate>();
                previous.candidates().forEach(value -> previousByKey.put(value.key(), value));
                var strengthened = qualified.stream()
                        .map(value -> new CandidateStrengthening(
                                value, policy.strengthening(previousByKey.get(value.key()), value)))
                        .filter(value -> value.change().strengthened())
                        .toList();
                var next = new NotificationState(
                        currentKeys, previous.marketFingerprint(), previous.integrityFingerprint(), now, qualified);
                if (added.isEmpty() && strengthened.isEmpty()) {
                    return NotificationStateChange.stateOnly(next, 0);
                }
                var notifications = new ArrayList<OutboundNotification>(2);
                if (!added.isEmpty()) {
                    var dedupe = previous.updatedAt().toEpochMilli() + "|"
                            + added.stream().map(InvestmentCandidate::key).sorted()
                            .collect(java.util.stream.Collectors.joining(","));
                    notifications.add(OutboundNotification.create(
                            "candidate-entry", dedupe,
                            entryMessage(added, marketSnapshot, trigger), now));
                }
                if (!strengthened.isEmpty()) {
                    var dedupe = previous.updatedAt().toEpochMilli() + "|"
                            + strengthened.stream().map(CandidateStrengthening::fingerprint).sorted()
                            .collect(java.util.stream.Collectors.joining(","));
                    notifications.add(OutboundNotification.create(
                            "candidate-strengthening", dedupe,
                            strengtheningMessage(strengthened, marketSnapshot, trigger), now));
                }
                return new NotificationStateChange<>(
                        next, notifications, added.size() + strengthened.size());
            });
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean checkMarketChanges(String trigger) {
        var snapshot = market.loadCurrent();
        var now = clock.instant();
        stateLock.lock();
        try {
            return stateRepository.updateAtomically(previous -> {
                if (previous.marketFingerprint().equals(snapshot.fingerprint())) {
                    return NotificationStateChange.stateOnly(previous, false);
                }
                var next = new NotificationState(
                        previous.candidateKeys(), snapshot.fingerprint(), previous.integrityFingerprint(),
                        now, previous.candidates());
                if (previous.marketFingerprint().isBlank()) {
                    return NotificationStateChange.stateOnly(next, false);
                }
                var message = OutboundNotification.create(
                        "market-change",
                        previous.updatedAt().toEpochMilli() + "|"
                                + previous.marketFingerprint() + "|" + snapshot.fingerprint(),
                        marketChangeMessage(snapshot, trigger),
                        now);
                return NotificationStateChange.withNotification(next, message, true);
            });
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public boolean dispatchWeeklyReport() {
        var text = market.loadWeeklyReportText();
        if (text == null || text.isBlank()) return false;
        var now = clock.instant();
        var date = java.time.LocalDate.ofInstant(now, SEOUL);
        var fields = java.time.temporal.WeekFields.ISO;
        var week = date.get(fields.weekBasedYear()) + "-W" + date.get(fields.weekOfWeekBasedYear());
        stateLock.lock();
        try {
            return stateRepository.updateAtomically(previous -> NotificationStateChange.withNotification(
                    previous,
                    OutboundNotification.create("weekly-report", week, text, now),
                    true));
        } finally {
            stateLock.unlock();
        }
    }

    private CompletableFuture<InvestmentCandidate> refreshAsync(InvestmentCandidate value) {
        try {
            return CompletableFuture.supplyAsync(() -> refresh(value), executor);
        } catch (RuntimeException rejected) {
            operationalEvents.degraded("notification", "candidate-refresh-submit", value.key(), rejected);
            // Executor saturation is still a failed *current* revalidation.
            // Returning the captured snapshot here would let an old BUY/bottom
            // signal pass the exact same eligibility filter as a successful
            // refresh and could produce a false Telegram entry alert.
            return CompletableFuture.completedFuture(
                    value.failClosed("현재 후보 재검증 작업을 시작하지 못해 알림 후보에서 제외됨"));
        }
    }

    private InvestmentCandidate refresh(InvestmentCandidate value) {
        try {
            return candidateRefresher.refresh(value);
        } catch (RuntimeException error) {
            operationalEvents.degraded("notification", "candidate-refresh", value.key(), error);
            // A captured candidate is only a universe seed. If current evidence
            // refresh fails, returning it would resurrect a stale BUY/bottom
            // signal and can create a false Telegram entry alert.
            return value.failClosed("현재 점수·가격 근거 재검증 실패로 알림 후보에서 제외됨");
        }
    }

    private String startupMessage(MarketNotificationSnapshot snapshot, List<InvestmentCandidate> values) {
        return "🚀 MacroSquare Java 서버 시작\n" + now() + marketSummary(snapshot)
                + candidateSummary(values) + breadthSummary(snapshot);
    }

    private String entryMessage(List<InvestmentCandidate> values, MarketNotificationSnapshot snapshot, String trigger) {
        var title = values.stream().allMatch(value -> value.kind() == CandidateKind.COMPANY)
                ? "🚨 신규 기업 진입 신호"
                : "🚨 신규 투자 진입 신호";
        return title + "\n" + now() + "\n점검: " + safe(trigger)
                + candidateSummary(values) + breadthSummary(snapshot);
    }

    private String strengtheningMessage(
            List<CandidateStrengthening> values,
            MarketNotificationSnapshot snapshot,
            String trigger
    ) {
        var lines = new ArrayList<String>();
        lines.add("🚀 기업 신호 강화");
        lines.add(now());
        lines.add("점검: " + safe(trigger));
        for (var index = 0; index < values.size(); index++) {
            var value = values.get(index);
            var candidate = value.candidate();
            var change = value.change();
            lines.add("");
            lines.add((index + 1) + ". " + candidate.symbol() + " — " + candidate.name());
            if (change.reversalBecameStrong()) {
                lines.add("   • 반전확인: ON → STRONG");
            }
            if (change.currentTotalScoreBand() != null) {
                lines.add("   • 기업점수 구간: " + change.previousTotalScoreBand()
                        + " → " + change.currentTotalScoreBand()
                        + " (현재 " + candidate.totalScore() + ")");
            }
            if (change.currentBuyScoreBand() != null) {
                lines.add("   • B점수 구간: " + change.previousBuyScoreBand()
                        + " → " + change.currentBuyScoreBand()
                        + " (현재 " + candidate.buyScore() + ")");
            }
            lines.add("   • 찐바닥: " + stateLabel(candidate.bottomState())
                    + (candidate.bottomScore() == null ? "" : " (" + candidate.bottomScore() + ')'));
            lines.add("   • 반전 확인 신호: " + reversalLabel(candidate));
            appendTechnicalTiming(lines, candidate);
            lines.add("   • 참고 실행 액션: " + candidate.action());
        }
        return String.join("\n", lines) + breadthSummary(snapshot);
    }

    private String marketChangeMessage(MarketNotificationSnapshot snapshot, String trigger) {
        return "⏰ MacroSquare 자산 신호 변경\n" + now() + "\n점검: " + safe(trigger)
                + marketSummary(snapshot) + breadthSummary(snapshot);
    }

    private static String marketSummary(MarketNotificationSnapshot snapshot) {
        var lines = new ArrayList<String>();
        lines.add("\n\n📋 전체 신호 현황");
        lines.add("🏛️ 국면: " + snapshot.regime() + " (" + snapshot.regimeScore() + "/100)");
        for (var signal : snapshot.signals()) {
            lines.add(signalEmoji(signal.action()) + " " + assetLabel(signal.asset()) + ": "
                    + signal.action() + " (" + signal.conditionsMet() + '/' + signal.conditionsTotal()
                    + " · 데이터 " + signal.dataCoveragePct() + "%)");
            if (!signal.constraint().isBlank()) lines.add("   ↳ " + signal.constraint());
        }
        if (!snapshot.allocations().isEmpty()) {
            lines.add("\n💼 포트폴리오 비중");
            snapshot.allocations().entrySet().stream().filter(entry -> entry.getValue() > 0)
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(entry -> lines.add("  " + allocationLabel(entry.getKey()) + ": " + entry.getValue() + '%'));
            lines.add("레버리지: " + (snapshot.leverageAllowed() ? "허용" : "불허"));
        }
        return String.join("\n", lines);
    }

    private static String candidateSummary(List<InvestmentCandidate> values) {
        if (values.isEmpty()) return "\n\n🟣 현재 진입 신호 충족 종목: 없음";
        var companies = values.stream().filter(value -> value.kind() == CandidateKind.COMPANY).toList();
        var cryptos = values.stream().filter(value -> value.kind() == CandidateKind.CRYPTO).toList();
        var sections = new ArrayList<String>();
        if (!companies.isEmpty()) sections.add("📈 회사\n" + formatCandidates(companies));
        if (!cryptos.isEmpty()) sections.add("🪙 코인\n" + formatCandidates(cryptos));
        var macdDisclosure = companies.isEmpty() ? ""
                : "MACD는 진입 타이밍 보조이며 단독 매수·매도 신호가 아님\n";
        return "\n\n🟣 현재 진입 신호 충족 종목"
                + "\n회사: 총점≥70 · B≥70 · 찐바닥 후보 이상 · 반전 ON 이상 (실행 액션 무관)"
                + "\n코인: 총점/B≥70 · 바닥 후보 이상 · STRONG BUY\n"
                + macdDisclosure
                + String.join("\n\n", sections);
    }

    private static String formatCandidates(List<InvestmentCandidate> values) {
        var lines = new ArrayList<String>();
        for (var index = 0; index < values.size(); index++) {
            var value = values.get(index);
            lines.add((index + 1) + ". " + value.symbol() + " — " + value.name());
            lines.add("   • 상태: " + stateLabel(value.bottomState())
                    + (value.bottomScore() == null ? "" : " (" + value.bottomScore() + ')'));
            lines.add("   • B점수: " + value.buyScore() + " / 총점: " + value.totalScore());
            lines.add("   • 참고 실행 액션: " + value.action());
            if (!value.classification().isBlank()) lines.add("   • 분류: " + value.classification());
            lines.add("   • 반전 확인 신호: " + reversalLabel(value));
            if (value.signalDate() != null) lines.add("   • 반전 확인일: " + value.signalDate());
            appendTechnicalTiming(lines, value);
            value.reasons().stream().limit(3).forEach(reason -> lines.add("   · " + reason));
        }
        return String.join("\n", lines);
    }

    private static void appendTechnicalTiming(List<String> lines, InvestmentCandidate value) {
        if (value.kind() != CandidateKind.COMPANY) return;
        var timing = value.technicalTiming();
        if (timing == null) {
            lines.add("   • MACD: 현재 계산 자료 부족");
            return;
        }
        lines.add("   • MACD 일봉: " + timeframeLabel(timing.daily(), "일"));
        lines.add("   • MACD 주봉: " + timeframeLabel(timing.weekly(), "주")
                + (timing.currentWeekProvisional() ? " · 이번 주 진행 중" : ""));
    }

    private static String timeframeLabel(TechnicalTimingEvidence.Timeframe value, String unit) {
        var fields = new ArrayList<String>();
        fields.add(crossLabel(value.latestCross(), value.periodsSinceCross(), unit));
        fields.add(positionLabel(value.position()));
        fields.add(histogramLabel(value.histogram()));
        fields.add(divergenceLabel(value, unit));
        var observed = value.asOf() == null ? "" : value.asOf() + " 기준 · ";
        return observed + String.join(" · ", fields);
    }

    private static String crossLabel(TechnicalTimingEvidence.Cross value, Integer age, String unit) {
        var label = switch (value) {
            case BULLISH_CROSS -> "상방 골든크로스";
            case BEARISH_CROSS -> "하방 데드크로스";
            case NONE -> "교차 없음";
            case UNAVAILABLE -> "교차 계산불가";
        };
        return age == null || value == TechnicalTimingEvidence.Cross.NONE
                || value == TechnicalTimingEvidence.Cross.UNAVAILABLE
                ? label : label + "(" + age + unit + " 전)";
    }

    private static String positionLabel(TechnicalTimingEvidence.Position value) {
        return switch (value) {
            case ABOVE_SIGNAL -> "시그널 위";
            case BELOW_SIGNAL -> "시그널 아래";
            case AT_SIGNAL -> "시그널선 접점";
            case UNAVAILABLE -> "위치 계산불가";
        };
    }

    private static String histogramLabel(TechnicalTimingEvidence.Histogram value) {
        return switch (value) {
            case EXPANDING_POSITIVE -> "양(확대)";
            case CONTRACTING_POSITIVE -> "양(둔화)";
            case EXPANDING_NEGATIVE -> "음(확대)";
            case CONTRACTING_NEGATIVE -> "음(축소)";
            case FLAT -> "히스토그램 보합";
            case UNAVAILABLE -> "히스토그램 계산불가";
        };
    }

    private static String divergenceLabel(TechnicalTimingEvidence.Timeframe value, String unit) {
        if (value.divergence() == TechnicalTimingEvidence.Divergence.UNAVAILABLE) return "다이버전스 계산불가";
        if (value.divergence() == TechnicalTimingEvidence.Divergence.NONE) return "다이버전스 없음";
        var direction = value.divergence() == TechnicalTimingEvidence.Divergence.BULLISH ? "상승" : "하락";
        var age = value.periodsSinceDivergence() == null
                ? "" : "(" + value.periodsSinceDivergence() + unit + " 전)";
        return value.divergenceActive()
                ? direction + " 다이버전스 ON" + age
                : "과거 " + direction + " 다이버전스" + age;
    }

    private static String breadthSummary(MarketNotificationSnapshot snapshot) {
        return snapshot.breadthLines().isEmpty() ? ""
                : "\n\n📡 시장 확인 게이트 · 기술/Breadth/유동성\n"
                + String.join("\n", snapshot.breadthLines());
    }

    private String now() {
        return CLOCK_FORMAT.format(clock.instant().atZone(SEOUL));
    }

    private static String reversalLabel(InvestmentCandidate value) {
        if ("STRONG".equals(value.reversalStatus())) return "ON(강함)";
        if ("ON".equals(value.reversalStatus())) return "ON(보통)";
        if ("EARLY".equals(value.reversalStatus())) return "초기(미확인)";
        return "OFF";
    }

    private static String stateLabel(BottomCandidateState value) {
        return switch (value) {
            case UNMET -> "미충족";
            case CANDIDATE -> "후보";
            case CONVICTION -> "확신";
        };
    }

    private static String signalEmoji(String value) {
        return switch (value) {
            case "STRONG_BUY", "STRONG BUY" -> "🟢";
            case "BUY" -> "🔵";
            case "REDUCE" -> "🟠";
            case "SELL" -> "🔴";
            default -> "⚪";
        };
    }

    private static String assetLabel(String value) {
        return switch (value) {
            case "NASDAQ" -> "나스닥";
            case "KOSPI" -> "코스피";
            case "GOLD" -> "금";
            case "SILVER" -> "은";
            case "COPPER" -> "구리";
            case "CASH" -> "현금";
            case "LEVERAGE" -> "레버리지";
            case "EMERGING" -> "신흥국";
            default -> value;
        };
    }

    private static String allocationLabel(String value) {
        return switch (value) {
            case "cash" -> "현금";
            case "nasdaq" -> "나스닥";
            case "leverage" -> "레버리지";
            case "gold" -> "금";
            case "silver" -> "은";
            case "copper" -> "구리/원자재";
            case "korea" -> "한국";
            case "emerging" -> "신흥국";
            default -> value;
        };
    }

    private static String safe(String value) {
        if (value == null) return "scheduled";
        var sanitized = value.replaceAll("[^a-zA-Z0-9가-힣._-]", "_");
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }

    private record CandidateStrengthening(
            InvestmentCandidate candidate,
            InvestmentCandidateStrengthening change
    ) {
        private String fingerprint() {
            return candidate.key() + ':' + candidate.reversalStatus() + ':'
                    + candidate.totalScore() / 5 * 5 + ':' + candidate.buyScore() / 5 * 5;
        }
    }
}
