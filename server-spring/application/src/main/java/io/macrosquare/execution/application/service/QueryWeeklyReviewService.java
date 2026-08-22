package io.macrosquare.execution.application.service;

import io.macrosquare.execution.application.model.WeeklyReviewMarketContext;
import io.macrosquare.execution.application.model.WeeklyReviewReport;
import io.macrosquare.execution.application.model.WeeklyReviewReport.EventReview;
import io.macrosquare.execution.application.model.WeeklyReviewReport.SignalReview;
import io.macrosquare.execution.application.port.in.QueryWeeklyReviewUseCase;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.LoadWeeklyReviewMarketContextPort;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment.SourceUnit;
import io.macrosquare.execution.domain.service.WeeklyPlanReviewPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class QueryWeeklyReviewService implements QueryWeeklyReviewUseCase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MAX_TRADE_LOG = 1_000;

    private final InvestmentPlanRepository plans;
    private final TradeLogRepository tradeLog;
    private final LoadWeeklyReviewMarketContextPort market;
    private final WeeklyPlanReviewPolicy policy;
    private final Clock clock;

    public QueryWeeklyReviewService(
            InvestmentPlanRepository plans,
            TradeLogRepository tradeLog,
            LoadWeeklyReviewMarketContextPort market,
            WeeklyPlanReviewPolicy policy,
            Clock clock
    ) {
        this.plans = Objects.requireNonNull(plans);
        this.tradeLog = Objects.requireNonNull(tradeLog);
        this.market = Objects.requireNonNull(market);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public WeeklyReviewReport review() {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var today = LocalDate.ofInstant(now, SEOUL);
        var context = market.loadCurrent();
        var plan = plans.load().orElseGet(() -> InvestmentPlan.defaults(now));
        var planReview = policy.evaluate(plan, context.recommendedAllocations(), tradeLog.recent(MAX_TRADE_LOG), now);
        var signals = context.signals().stream().map(value -> new SignalReview(
                value.asset(), value.action(), value.conditionsMet() + "/" + value.conditionsTotal(),
                value.dataCoveragePct())).toList();
        var topReasons = context.signals().stream()
                .sorted(Comparator.comparingInt(QueryWeeklyReviewService::signalRank).reversed())
                .flatMap(value -> primaryReasons(value).stream()
                        .map(reason -> '[' + value.asset() + ' ' + value.action() + "] " + reason))
                .limit(5)
                .toList();
        var warnings = new ArrayList<>(context.warnings());
        if (planReview.holdings().sourceUnit() == SourceUnit.KRW_ABSOLUTE) {
            warnings.add("기존 KRW 보유금액을 " + formatKrw(planReview.holdings().denominator())
                    + "원 총 운용자본 기준 비중으로 환산했습니다. 원금액과 초과 노출은 숨기지 않습니다.");
        }
        warnings.addAll(planReview.holdings().cautions());
        var events = context.events().stream()
                .filter(value -> !value.date().isBefore(today))
                .sorted(Comparator.comparing(WeeklyReviewMarketContext.MarketEvent::date))
                .limit(5)
                .map(value -> new EventReview(
                        value.name(), value.date(), ChronoUnit.DAYS.between(today, value.date()), value.importance()))
                .toList();
        var from = today.minusDays(6);
        var text = formatText(
                from, today, context, signals, topReasons, warnings, events, planReview.ruleViolations(), now);
        return new WeeklyReviewReport(
                now, from, today, context.regime(), context.regimeScore(), signals, topReasons,
                warnings, events, planReview.ruleViolations(), planReview.holdings(), planReview.drift(), text);
    }

    private static int signalRank(WeeklyReviewMarketContext.MarketSignal signal) {
        return switch (signal.action()) {
            case "STRONG_BUY" -> 5;
            case "BUY" -> 4;
            case "HOLD" -> 3;
            case "REDUCE" -> 2;
            case "SELL" -> 1;
            default -> 0;
        };
    }

    private static List<String> primaryReasons(WeeklyReviewMarketContext.MarketSignal signal) {
        var actionGate = signal.unmetReasons().stream()
                .filter(value -> value.startsWith("⚠"))
                .findFirst();
        if (actionGate.isPresent()) return List.of(actionGate.get());
        if ((signal.action().equals("HOLD") || signal.action().equals("REDUCE") || signal.action().equals("SELL"))
                && !signal.unmetReasons().isEmpty()) {
            return List.of(signal.unmetReasons().getFirst());
        }
        return signal.reasons().stream().limit(1).toList();
    }

    private static String formatText(
            LocalDate from,
            LocalDate to,
            WeeklyReviewMarketContext context,
            List<SignalReview> signals,
            List<String> reasons,
            List<String> warnings,
            List<EventReview> events,
            List<String> violations,
            java.time.Instant generatedAt
    ) {
        var lines = new ArrayList<String>();
        lines.add("📊 MacroSquare Weekly Report (" + from + " ~ " + to + ")");
        lines.add("");
        lines.add("🎯 레짐: " + context.regime() + " (" + context.regimeScore() + "/100)");
        if (!signals.isEmpty()) {
            lines.add("");
            lines.add("📈 자산별 신호:");
            signals.forEach(value -> lines.add("  - " + value.asset() + ": " + value.signal()
                    + " (" + value.met() + " · 데이터 " + value.dataCoveragePct() + "%)"));
        }
        appendSection(lines, "💡 핵심 근거:", reasons);
        appendSection(lines, "⚠️ 경고:", warnings);
        if (!events.isEmpty()) {
            lines.add("");
            lines.add("📅 다가오는 이벤트:");
            events.forEach(value -> lines.add("  • " + value.event() + ": D-" + value.dday() + " (" + value.date() + ')'));
        }
        appendSection(lines, "🚨 계획 규칙 점검:", violations);
        lines.add("");
        lines.add("산출: " + generatedAt + " · 현재 Spring 스냅샷/투자계획 기준");
        return String.join("\n", lines);
    }

    private static void appendSection(List<String> target, String title, List<String> values) {
        if (values.isEmpty()) return;
        target.add("");
        target.add(title);
        values.forEach(value -> target.add("  • " + value));
    }

    private static String formatKrw(double value) {
        return String.format(java.util.Locale.ROOT, "%,.0f", value);
    }
}
