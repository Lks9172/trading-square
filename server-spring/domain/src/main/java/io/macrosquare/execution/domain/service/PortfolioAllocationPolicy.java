package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment;
import io.macrosquare.execution.domain.model.PortfolioAllocationAssessment.SourceUnit;
import io.macrosquare.execution.domain.model.PortfolioDriftAssessment;
import io.macrosquare.execution.domain.model.PortfolioDriftAssessment.WeightDrift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Unit-aware portfolio policy shared by the plan projection and weekly review. */
public final class PortfolioAllocationPolicy {

    private static final double PERCENT_EPSILON = 0.01;
    private static final double LEGACY_KRW_TOTAL_THRESHOLD = 1_000;

    public PortfolioAllocationAssessment assess(InvestmentPlan plan) {
        var source = plan.currentHoldings();
        if (source == null || source.isEmpty()) {
            return new PortfolioAllocationAssessment(
                    SourceUnit.EMPTY, Map.of(), Map.of(), 0, 0, 0, 100, 0,
                    false, java.util.List.of());
        }

        var sourceTotal = source.values().stream().mapToDouble(Double::doubleValue).sum();
        // A single weight can legitimately exceed 100% in a leveraged account.
        // Legacy KRW rows are orders of magnitude larger, so classify on the
        // aggregate scale instead of silently treating a 115% weight as 115 won.
        var absolute = sourceTotal > LEGACY_KRW_TOTAL_THRESHOLD + PERCENT_EPSILON;
        if (absolute) return absolute(plan, source, sourceTotal);
        return percentages(source, sourceTotal);
    }

    public PortfolioDriftAssessment drift(
            PortfolioAllocationAssessment actual,
            Map<String, ? extends Number> recommended
    ) {
        var assets = new TreeSet<String>();
        assets.addAll(actual.percentages().keySet());
        if (recommended != null) assets.addAll(recommended.keySet());
        var weights = new ArrayList<WeightDrift>();
        for (var asset : assets) {
            var targetNumber = recommended == null ? null : recommended.get(asset);
            var target = targetNumber == null ? 0 : targetNumber.doubleValue();
            var current = actual.percentages().getOrDefault(asset, 0d);
            weights.add(new WeightDrift(asset, round2(target), round2(current), round2(Math.abs(target - current))));
        }
        weights.sort(Comparator.comparingDouble(WeightDrift::differencePct).reversed()
                .thenComparing(WeightDrift::asset));
        var total = weights.stream().mapToDouble(WeightDrift::differencePct).sum() / 2;
        return new PortfolioDriftAssessment(round2(total), weights);
    }

    private static PortfolioAllocationAssessment absolute(
            InvestmentPlan plan,
            Map<String, Double> source,
            double sourceTotal
    ) {
        var cautions = new ArrayList<String>();
        var configuredCapital = plan.totalCapitalKrw() == null ? 0d : plan.totalCapitalKrw().doubleValue();
        var denominator = configuredCapital > 0 ? configuredCapital : sourceTotal;
        if (configuredCapital <= 0) {
            cautions.add("총 운용자본이 없어 보유금액 합계를 임시 환산 기준으로 사용했습니다. 정확한 자본을 입력하면 실제 노출 비중을 계산합니다.");
        }
        var values = divide(source, denominator);
        var allocated = values.values().stream().mapToDouble(Double::doubleValue).sum();
        var overAllocated = Math.max(0, allocated - 100);
        if (overAllocated > PERCENT_EPSILON) {
            cautions.add("보유금액 합계가 총 운용자본의 " + formatPct(allocated)
                    + "%로 " + formatPct(overAllocated)
                    + "%p 초과합니다. 부채·중복 집계·총자본 입력을 확인하세요.");
        }
        return new PortfolioAllocationAssessment(
                SourceUnit.KRW_ABSOLUTE,
                source,
                values,
                round2(sourceTotal),
                round2(denominator),
                round2(allocated),
                round2(Math.max(0, 100 - allocated)),
                round2(overAllocated),
                true,
                cautions
        );
    }

    private static PortfolioAllocationAssessment percentages(
            Map<String, Double> source,
            double sourceTotal
    ) {
        var cautions = new ArrayList<String>();
        var values = rounded(source);
        var allocated = values.values().stream().mapToDouble(Double::doubleValue).sum();
        var overAllocated = Math.max(0, allocated - 100);
        if (overAllocated > PERCENT_EPSILON) {
            cautions.add("입력 비중 합계가 " + formatPct(allocated) + "%로 "
                    + formatPct(overAllocated) + "%p 초과합니다. 초과분을 숨기지 않고 그대로 표시합니다.");
        }
        return new PortfolioAllocationAssessment(
                SourceUnit.PERCENT,
                source,
                values,
                round2(sourceTotal),
                100,
                round2(allocated),
                round2(Math.max(0, 100 - allocated)),
                round2(overAllocated),
                false,
                cautions
        );
    }

    private static Map<String, Double> divide(Map<String, Double> source, double denominator) {
        if (denominator <= 0) return Map.of();
        var result = new LinkedHashMap<String, Double>();
        source.forEach((key, value) -> result.put(key, round2(value * 100 / denominator)));
        return result;
    }

    private static Map<String, Double> rounded(Map<String, Double> source) {
        var result = new LinkedHashMap<String, Double>();
        source.forEach((key, value) -> result.put(key, round2(value)));
        return result;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String formatPct(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
