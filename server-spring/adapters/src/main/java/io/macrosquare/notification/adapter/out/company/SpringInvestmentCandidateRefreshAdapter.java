package io.macrosquare.notification.adapter.out.company;

import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.model.CompanyMacdTimingSnapshot;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyInvestmentAction;
import io.macrosquare.company.domain.investment.CompanyPriceStructureActionGuard;
import io.macrosquare.notification.application.port.out.RefreshInvestmentCandidatePort;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import io.macrosquare.notification.domain.TechnicalTimingEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;

/**
 * Anti-corruption adapter between the company bounded context and notification candidates.
 *
 * <p>Each evidence group is refreshed independently. Candidate alerts are
 * intentionally fail-closed: unavailable or non-comparable current evidence
 * may remain visible in research views, but can never retain an old qualifying
 * score or reversal state in the notification path.</p>
 */
public final class SpringInvestmentCandidateRefreshAdapter implements RefreshInvestmentCandidatePort {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpringInvestmentCandidateRefreshAdapter.class);
    private static final Duration SUMMARY_MAXIMUM_AGE = Duration.ofHours(2);

    private final EvaluateCompanyResearchParityUseCase companyResearch;
    private final EvaluateCompanyPriceSignalParityUseCase companyPriceSignals;
    private final CompanyResearchSummaryRepository companySummaries;
    private final CompanyPriceStructureActionGuard priceStructureActionGuard;
    private final Clock clock;

    public SpringInvestmentCandidateRefreshAdapter(
            EvaluateCompanyResearchParityUseCase companyResearch,
            EvaluateCompanyPriceSignalParityUseCase companyPriceSignals
    ) {
        this(companyResearch, companyPriceSignals, null, new CompanyPriceStructureActionGuard(), Clock.systemUTC());
    }

    public SpringInvestmentCandidateRefreshAdapter(
            EvaluateCompanyResearchParityUseCase companyResearch,
            EvaluateCompanyPriceSignalParityUseCase companyPriceSignals,
            CompanyResearchSummaryRepository companySummaries
    ) {
        this(companyResearch, companyPriceSignals, companySummaries,
                new CompanyPriceStructureActionGuard(), Clock.systemUTC());
    }

    public SpringInvestmentCandidateRefreshAdapter(
            EvaluateCompanyResearchParityUseCase companyResearch,
            EvaluateCompanyPriceSignalParityUseCase companyPriceSignals,
            CompanyPriceStructureActionGuard priceStructureActionGuard
    ) {
        this(companyResearch, companyPriceSignals, null, priceStructureActionGuard, Clock.systemUTC());
    }

    public SpringInvestmentCandidateRefreshAdapter(
            EvaluateCompanyResearchParityUseCase companyResearch,
            EvaluateCompanyPriceSignalParityUseCase companyPriceSignals,
            CompanyResearchSummaryRepository companySummaries,
            CompanyPriceStructureActionGuard priceStructureActionGuard
    ) {
        this(companyResearch, companyPriceSignals, companySummaries, priceStructureActionGuard, Clock.systemUTC());
    }

    public SpringInvestmentCandidateRefreshAdapter(
            EvaluateCompanyResearchParityUseCase companyResearch,
            EvaluateCompanyPriceSignalParityUseCase companyPriceSignals,
            CompanyResearchSummaryRepository companySummaries,
            CompanyPriceStructureActionGuard priceStructureActionGuard,
            Clock clock
    ) {
        this.companyResearch = Objects.requireNonNull(companyResearch);
        this.companyPriceSignals = Objects.requireNonNull(companyPriceSignals);
        this.companySummaries = companySummaries;
        this.priceStructureActionGuard = Objects.requireNonNull(priceStructureActionGuard);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public InvestmentCandidate refresh(InvestmentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.kind() != CandidateKind.COMPANY) return candidate;

        var totalScore = candidate.totalScore();
        var buyScore = candidate.buyScore();
        var action = candidate.action();
        var summaryResolved = false;
        if (companySummaries != null) {
            try {
                var summary = companySummaries.find(candidate.symbol()).orElse(null);
                if (summary != null) {
                    if (summary.notificationEvidenceCurrentAt(clock.instant(), SUMMARY_MAXIMUM_AGE)) {
                        var persistedReasons = summary.priceSignalReasons().isEmpty()
                                ? candidate.reasons() : summary.priceSignalReasons();
                        return new InvestmentCandidate(
                                candidate.kind(), candidate.symbol(), candidate.name(), candidate.classification(),
                                bottomState(summary.confirmedBottomState()), summary.confirmedBottomScore(),
                                summary.totalScore(), summary.buyScore(), summary.executionAction(),
                                summary.confirmedBottomSignalDate(), summary.reversalStatus(), summary.reversalScore(),
                                persistedReasons, technicalTiming(summary.macdTiming())
                        );
                    }
                    if (summary.scoreComparableAt(clock.instant(), SUMMARY_MAXIMUM_AGE)) {
                        summaryResolved = true;
                        totalScore = summary.totalScore();
                        buyScore = summary.buyScore();
                        action = summary.executionAction();
                    }
                }
            } catch (RuntimeException error) {
                LOGGER.debug("Unable to load persisted company scores for notification candidate {}",
                        candidate.symbol(), error);
            }
        }
        if (!summaryResolved) {
            totalScore = 0;
            buyScore = 0;
            action = "HOLD";
            try {
                var report = companyResearch.evaluate(candidate.symbol());
                if (report.scoreComparable()) {
                    totalScore = report.springScore().totalScore();
                    buyScore = report.springBuyScore().buyScore();
                    // Core score refresh alone cannot authorize a trade. Only a
                    // versioned summary produced by the full current decision
                    // stack can do that; fallback remains fail-closed.
                    action = "HOLD";
                } else {
                    totalScore = 0;
                    buyScore = 0;
                    action = "HOLD";
                }
            } catch (RuntimeException error) {
                totalScore = 0;
                buyScore = 0;
                action = "HOLD";
                LOGGER.debug("Suppressing stale company scores for notification candidate {}",
                        candidate.symbol(), error);
            }
        }

        var bottomState = candidate.bottomState();
        var bottomScore = candidate.bottomScore();
        var signalDate = candidate.signalDate();
        var reversalStatus = candidate.reversalStatus();
        var reversalScore = candidate.reversalScore();
        var reasons = candidate.reasons();
        var technicalTiming = candidate.technicalTiming();
        try {
            var direct = companyPriceSignals.evaluate(candidate.symbol()).spring();
            var bottom = direct.confirmedBottom();
            var reversal = direct.reversalConfirmation();
            bottomState = switch (bottom.state()) {
                case UNMET -> BottomCandidateState.UNMET;
                case CANDIDATE -> BottomCandidateState.CANDIDATE;
                case CONVICTION -> BottomCandidateState.CONVICTION;
            };
            bottomScore = bottom.score();
            signalDate = bottom.signalDate();
            reversalStatus = reversal.status().name();
            reversalScore = reversal.score();
            technicalTiming = technicalTiming(direct.macdMomentum());
            var refreshedReasons = new LinkedHashSet<String>();
            var structure = direct.priceStructure();
            if (structure != null) {
                refreshedReasons.add(priceStructureSummary(structure));
                var guarded = priceStructureActionGuard.evaluate(parseAction(action), structure);
                action = action(guarded.action());
                if (!guarded.reason().isBlank()) {
                    refreshedReasons.add("실행 제한: " + guarded.reason());
                }
            }
            // Telegram renders the first three reasons. Preserve one direct bottom/volume
            // observation between the structure summary and the structure risk so the
            // newly-added video evidence never hides the original bottom evidence.
            bottom.reasons().stream().limit(1).forEach(refreshedReasons::add);
            if (structure != null) structure.cautions().stream().limit(1).forEach(refreshedReasons::add);
            if (structure != null) structure.reasons().stream().limit(1).forEach(refreshedReasons::add);
            refreshedReasons.addAll(bottom.reasons());
            reasons = List.copyOf(refreshedReasons);
        } catch (RuntimeException error) {
            bottomState = BottomCandidateState.UNMET;
            bottomScore = null;
            signalDate = null;
            reversalStatus = "OFF";
            reversalScore = null;
            technicalTiming = null;
            reasons = List.of("현재 가격·corporate-action 기준 검증 실패로 알림 후보에서 제외됨");
            LOGGER.debug("Suppressing stale price signals for notification candidate {}", candidate.symbol(), error);
        }

        return new InvestmentCandidate(
                candidate.kind(), candidate.symbol(), candidate.name(), candidate.classification(),
                bottomState, bottomScore, totalScore, buyScore, action, signalDate,
                reversalStatus, reversalScore, reasons, technicalTiming
        );
    }

    private static TechnicalTimingEvidence technicalTiming(CompanyMacdTimingSnapshot value) {
        if (value == null) return null;
        return new TechnicalTimingEvidence(
                technicalTimeframe(value.daily()),
                technicalTimeframe(value.weekly()),
                value.currentWeekProvisional()
        );
    }

    private static TechnicalTimingEvidence.Timeframe technicalTimeframe(
            CompanyMacdTimingSnapshot.Timeframe value
    ) {
        return new TechnicalTimingEvidence.Timeframe(
                value.asOf(),
                enumValue(TechnicalTimingEvidence.Position.class, value.position(),
                        TechnicalTimingEvidence.Position.UNAVAILABLE),
                enumValue(TechnicalTimingEvidence.Cross.class, value.latestCross(),
                        TechnicalTimingEvidence.Cross.UNAVAILABLE),
                value.crossDate(),
                value.periodsSinceCross(),
                enumValue(TechnicalTimingEvidence.Histogram.class, value.histogramState(),
                        TechnicalTimingEvidence.Histogram.UNAVAILABLE),
                enumValue(TechnicalTimingEvidence.Divergence.class, value.divergence(),
                        TechnicalTimingEvidence.Divergence.UNAVAILABLE),
                value.divergenceConfirmedDate(),
                value.periodsSinceDivergence(),
                value.divergenceActive()
        );
    }

    private static TechnicalTimingEvidence technicalTiming(
            io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis value
    ) {
        if (value == null) return null;
        return new TechnicalTimingEvidence(
                technicalTimeframe(value.daily()),
                technicalTimeframe(value.weekly()),
                value.currentWeekProvisional()
        );
    }

    private static TechnicalTimingEvidence.Timeframe technicalTimeframe(
            io.macrosquare.technical.domain.MacdSignalAnalysis value
    ) {
        return new TechnicalTimingEvidence.Timeframe(
                value.asOf(),
                TechnicalTimingEvidence.Position.valueOf(value.position().name()),
                TechnicalTimingEvidence.Cross.valueOf(value.latestCross().name()),
                value.crossDate(),
                value.sessionsSinceCross(),
                TechnicalTimingEvidence.Histogram.valueOf(value.histogramState().name()),
                TechnicalTimingEvidence.Divergence.valueOf(value.divergence().name()),
                value.divergenceConfirmedDate(),
                value.sessionsSinceDivergence(),
                value.divergenceActive()
        );
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return value == null ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static BottomCandidateState bottomState(String value) {
        return switch (value) {
            case "CONVICTION" -> BottomCandidateState.CONVICTION;
            case "CANDIDATE" -> BottomCandidateState.CANDIDATE;
            default -> BottomCandidateState.UNMET;
        };
    }

    private static CompanyInvestmentAction parseAction(String action) {
        if (action == null) return CompanyInvestmentAction.HOLD;
        try {
            return CompanyInvestmentAction.valueOf(action.trim().toUpperCase(java.util.Locale.ROOT)
                    .replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            return CompanyInvestmentAction.HOLD;
        }
    }

    private static String action(CompanyInvestmentAction action) {
        return action.name().replace('_', ' ');
    }

    private static String priceStructureSummary(PriceStructureAnalysis value) {
        var flags = new StringBuilder();
        if (value.volumeBreakout()) flags.append(" · 거래량 돌파");
        if (value.stopHuntReclaim()) flags.append(" · 스톱헌트 회복");
        if (value.oversoldConfluence()) flags.append(" · RSI 다중확인");
        var fibonacci = value.fibonacci();
        if (fibonacci != null
                && fibonacci.swingDirection()
                != io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis.SwingDirection.UNAVAILABLE
                && fibonacci.nearestRatio() != null) {
            flags.append(" · 피보 ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", fibonacci.nearestRatio()))
                    .append("/합치")
                    .append(fibonacci.confluenceScore());
            if (fibonacci.zoneState()
                    == io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis.ZoneState.LAST_DEFENSE_BROKEN) {
                flags.append("(0.786 이탈)");
            }
        }
        return "가격구조 %d/100(확률 아님) · %s · 훼손 %s · 회복 %s · 위치 %s%s"
                .formatted(
                        value.score(),
                        trendLabel(value.trendState()),
                        reversalLabel(value.bearishReversalStage()),
                        recoveryLabel(value.recoveryStage()),
                        locationLabel(value.priceLocation()),
                        flags
                );
    }

    private static String trendLabel(PriceStructureAnalysis.TrendState value) {
        return switch (value) {
            case UPTREND -> "상승";
            case RANGE -> "횡보";
            case DOWNTREND -> "하락";
            case TRANSITION -> "전환";
            case UNAVAILABLE -> "계산불가";
        };
    }

    private static String reversalLabel(PriceStructureAnalysis.BearishReversalStage value) {
        return switch (value) {
            case INTACT -> "정상";
            case MOMENTUM_WEAKENING -> "1단계";
            case STRUCTURAL_CRACK -> "2단계";
            case PRIOR_LOW_BROKEN -> "3단계";
            case UNAVAILABLE -> "계산불가";
        };
    }

    private static String recoveryLabel(PriceStructureAnalysis.RecoveryStage value) {
        return switch (value) {
            case NONE -> "없음";
            case BASE_BUILDING -> "바닥다지기";
            case REBOUND -> "반등";
            case STRUCTURE_BREAK -> "고점돌파";
            case RETEST_HELD -> "돌파지지";
            case UNAVAILABLE -> "계산불가";
        };
    }

    private static String locationLabel(PriceStructureAnalysis.PriceLocation value) {
        return switch (value) {
            case BREAKOUT -> "돌파";
            case LOWER_CHANNEL -> "채널하단";
            case SUPPORT_ZONE -> "지지";
            case MID_CHANNEL -> "채널중단";
            case RESISTANCE_ZONE -> "저항";
            case UPPER_CHANNEL -> "채널상단";
            case BREAKDOWN -> "지지이탈";
            case UNAVAILABLE -> "계산불가";
        };
    }
}
