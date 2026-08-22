package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment.SourceUnit;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.WeeklyPlanReview;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure weekly discipline and allocation review. */
public final class WeeklyPlanReviewPolicy {

    public static final double MATERIAL_DRIFT_PCT = 10;
    private final PortfolioAllocationPolicy allocations;

    public WeeklyPlanReviewPolicy(PortfolioAllocationPolicy allocations) {
        this.allocations = java.util.Objects.requireNonNull(allocations);
    }

    public WeeklyPlanReview evaluate(
            InvestmentPlan plan,
            Map<String, ? extends Number> recommended,
            List<TradeLogEntry> tradeLog,
            Instant now
    ) {
        var holdings = allocations.assess(plan);
        var drift = allocations.drift(holdings, recommended == null ? Map.of() : recommended);
        var violations = new ArrayList<String>();
        var entries = tradeLog == null ? List.<TradeLogEntry>of() : tradeLog;

        discipline(entries, now, violations);
        recommendedAllocation(plan, recommended, violations);
        if (holdings.sourceUnit() != SourceUnit.EMPTY) {
            if (holdings.overAllocatedPct() >= 1) {
                violations.add("⚠️ 실제 보유 노출 " + formatPct(holdings.allocatedPct())
                        + "% — 총 운용자본을 " + formatPct(holdings.overAllocatedPct())
                        + "%p 초과하므로 부채·중복 집계·총자본 입력을 확인하세요.");
            }
            var material = drift.exceeding(MATERIAL_DRIFT_PCT);
            if (!material.isEmpty()) {
                var top = material.stream().limit(3)
                        .map(value -> value.asset() + ' ' + formatPct(value.differencePct()) + "%p")
                        .toList();
                violations.add("⚠️ 권고 대비 실제 비중 차이 10%p 이상 — " + String.join(", ", top));
            }
        }
        return new WeeklyPlanReview(holdings, drift, violations);
    }

    private static void discipline(List<TradeLogEntry> entries, Instant now, List<String> violations) {
        var sevenDaysAgo = now.minus(Duration.ofDays(7));
        var observations = entries.stream()
                .filter(value -> value.kind() == TradeLogKind.OBSERVATION)
                .filter(value -> !value.timestamp().isBefore(sevenDaysAgo))
                .count();
        if (observations == 0) {
            violations.add("⚠️ 최근 7일 복기 기록 0회 — 매수·매도 근거를 한 번 이상 기록하세요.");
        }

        var dayAgo = now.minus(Duration.ofHours(24));
        var actionCounts = new LinkedHashMap<String, Integer>();
        entries.stream()
                .filter(value -> value.kind() == TradeLogKind.USER_ACTION)
                .filter(value -> !value.timestamp().isBefore(dayAgo))
                .filter(value -> value.asset() != null && !value.asset().isBlank())
                .forEach(value -> actionCounts.merge(value.asset().toUpperCase(), 1, Integer::sum));
        actionCounts.entrySet().stream().filter(value -> value.getValue() >= 2).findFirst()
                .ifPresent(value -> violations.add(
                        "⚠️ 24시간 내 " + value.getKey() + " 매매 변경 " + value.getValue() + "회 — 충동 매매 여부를 점검하세요."));

        var thirtyDaysAgo = now.minus(Duration.ofDays(30));
        var horizonChanges = entries.stream()
                .filter(value -> value.timestamp().isAfter(thirtyDaysAgo))
                .filter(value -> value.notes() != null && value.notes().startsWith("horizon change:"))
                .count();
        if (horizonChanges >= 2) {
            violations.add("⚠️ 최근 30일 투자 시계열 변경 " + horizonChanges + "회 — 전략 기준이 흔들리는지 점검하세요.");
        }

        var fourWeeksAgo = now.minus(Duration.ofDays(28));
        var against = entries.stream()
                .filter(value -> !value.timestamp().isBefore(fourWeeksAgo))
                .filter(value -> Boolean.TRUE.equals(value.againstSystemRecommendation()))
                .count();
        if (against >= 5) {
            violations.add("⚠️ 최근 4주 시스템 권고 반대 행동 " + against + "회 — 반복 행동 패턴을 복기하세요.");
        }
    }

    private static void recommendedAllocation(
            InvestmentPlan plan,
            Map<String, ? extends Number> recommended,
            List<String> violations
    ) {
        if (recommended == null || recommended.isEmpty()) return;
        var leverage = number(recommended.get("leverage"));
        if (leverage > plan.leverageMaxPct()) {
            violations.add("⚠️ 권고 레버리지 " + formatPct(leverage) + "%가 계획 상한 "
                    + formatPct(plan.leverageMaxPct()) + "%를 초과합니다.");
        }
        var cash = number(recommended.get("cash"));
        var minimum = switch (plan.horizon()) {
            case SHORT -> 20;
            case LONG -> 5;
            case MEDIUM -> 10;
        };
        if (cash < minimum) {
            violations.add("⚠️ 권고 현금 " + formatPct(cash) + "%가 " + plan.horizon().value()
                    + " 시계열 최소 " + minimum + "%보다 낮습니다.");
        }
    }

    private static double number(Number value) {
        return value == null ? 0 : value.doubleValue();
    }

    private static String formatPct(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
