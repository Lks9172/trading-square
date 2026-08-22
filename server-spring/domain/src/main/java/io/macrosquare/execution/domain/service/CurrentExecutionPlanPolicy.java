package io.macrosquare.execution.domain.service;

import io.macrosquare.execution.domain.model.CurrentExecutionEvidence;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan.Action;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan.ExitRule;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan.Stage;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan.StageStatus;
import io.macrosquare.execution.domain.model.CurrentExecutionPlan.Timing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns current recommendations into a conservative, manual execution checklist.
 * It never invents target returns and never marks a tranche as filled.
 */
public final class CurrentExecutionPlanPolicy {

    private static final List<String> ASSET_ORDER = List.of(
            "NASDAQ", "KOSPI", "GOLD", "SILVER", "COPPER", "EMERGING", "LEVERAGE");
    private static final Map<String, String> ALLOCATION_KEYS = Map.of(
            "NASDAQ", "nasdaq", "KOSPI", "korea", "GOLD", "gold", "SILVER", "silver",
            "COPPER", "copper", "EMERGING", "emerging", "LEVERAGE", "leverage");
    private static final Map<String, String> PRICE_KEYS = Map.of(
            "NASDAQ", "NASDAQ", "KOSPI", "KOSPI", "GOLD", "GOLD", "SILVER", "SILVER",
            "COPPER", "COPPER", "EMERGING", "EWZ", "LEVERAGE", "TQQQ");

    public List<CurrentExecutionPlan> evaluate(CurrentExecutionEvidence evidence) {
        var signals = new LinkedHashMap<String, CurrentExecutionEvidence.SignalEvidence>();
        evidence.signals().forEach(signal -> signals.put(signal.asset(), signal));
        var result = new ArrayList<CurrentExecutionPlan>();
        for (var asset : ASSET_ORDER) {
            var signal = signals.get(asset);
            if (signal != null) result.add(plan(asset, signal, evidence));
        }
        return List.copyOf(result);
    }

    private static CurrentExecutionPlan plan(
            String asset,
            CurrentExecutionEvidence.SignalEvidence signal,
            CurrentExecutionEvidence evidence
    ) {
        var price = number(evidence.rawValues(), PRICE_KEYS.get(asset));
        var timing = timing(asset, evidence);
        var strictBuyNow = signal.action() == CurrentExecutionEvidence.SignalAction.STRONG_BUY
                && signal.dataCoveragePct() >= 85
                && timing.macroAligned()
                && timing.sectorAligned()
                && timing.flowConfirmed()
                && timing.chartConfirmed()
                && !timing.overheatingRisk();
        var action = switch (signal.action()) {
            case STRONG_BUY -> strictBuyNow ? Action.BUY_NOW : Action.SCALE_IN;
            case BUY -> Action.SCALE_IN;
            case HOLD -> "LEVERAGE".equals(asset) ? Action.AVOID : Action.HOLD;
            case REDUCE -> Action.TAKE_PROFIT;
            case SELL -> Action.EXIT;
        };
        var stages = action == Action.BUY_NOW || action == Action.SCALE_IN
                ? stages(asset, price, signal, timing, evidence.derivedValues())
                : List.<Stage>of();
        var buySide = action == Action.BUY_NOW || action == Action.SCALE_IN;
        return new CurrentExecutionPlan(
                asset,
                action,
                label(action),
                price,
                evidence.targetAllocations().getOrDefault(ALLOCATION_KEYS.get(asset), 0),
                stages,
                new ExitRule(null, buySide
                        ? "가격구조 하드 게이트 훼손 또는 사전에 정한 손실 한도 도달 시 재평가"
                        : "— "),
                new ExitRule(null, buySide
                        ? "과열·목표 비중 초과 시 분할 축소; 고정 수익률을 보장하지 않음"
                        : "— "),
                "GOLD".equals(asset) ? 14 : 7,
                primaryReason(signal, action, strictBuyNow),
                timing
        );
    }

    private static List<Stage> stages(
            String asset,
            Double currentPrice,
            CurrentExecutionEvidence.SignalEvidence signal,
            Timing timing,
            Map<String, Double> derived
    ) {
        var currentReady = currentPrice != null
                && signal.action() == CurrentExecutionEvidence.SignalAction.STRONG_BUY
                && signal.dataCoveragePct() >= 85
                && timing.macroAligned()
                && timing.sectorAligned()
                && timing.flowConfirmed()
                && timing.chartConfirmed()
                && !timing.overheatingRisk();
        var result = new ArrayList<Stage>();
        result.add(new Stage(
                1, 30,
                firstStageCondition(currentPrice, signal, timing, currentReady),
                currentReady ? currentPrice : null,
                currentReady ? StageStatus.READY : StageStatus.PENDING));

        var support = support(asset, currentPrice, derived, false);
        result.add(new Stage(
                2, 30,
                support == null
                        ? "자체 가격·거래량 또는 확인 가능한 지지 구조가 없어 추가 매수 대기"
                        : support.condition(),
                support == null ? null : support.price(),
                StageStatus.PENDING));
        var deepSupport = support(asset, currentPrice, derived, true);
        result.add(new Stage(
                3, 40,
                deepSupport == null
                        ? "거시·수급·가격구조가 함께 재확인될 때만 마지막 차수 검토"
                        : deepSupport.condition(),
                deepSupport == null ? null : deepSupport.price(),
                StageStatus.PENDING));
        return List.copyOf(result);
    }

    private static String firstStageCondition(
            Double currentPrice,
            CurrentExecutionEvidence.SignalEvidence signal,
            Timing timing,
            boolean currentReady
    ) {
        if (currentReady) return "현재 신호를 재확인한 뒤 1차만 수동 집행";
        if (currentPrice == null) return "현재가 확인 전 대기";
        if (signal.dataCoveragePct() < 85) return "필수 데이터 커버리지 85% 확보 전 대기";
        if (!timing.macroAligned()) return "거시 국면 재정렬 확인 전 대기";
        if (timing.overheatingRisk()) return "과열·이격 완화 또는 지지 재시험 전 추격 대기";
        if (!timing.sectorAligned()) return "섹터 상대강도 정합 확인 전 대기";
        if (!timing.flowConfirmed()) return "독립 수급 확인 전 대기";
        if (!timing.chartConfirmed()) return "가격구조 확인 전 대기";
        if (signal.action() != CurrentExecutionEvidence.SignalAction.STRONG_BUY) {
            return "STRONG BUY와 후속 확인이 함께 충족될 때 1차 검토";
        }
        return "현재 진입 조건 재확인 전 대기";
    }

    private static Support support(
            String asset,
            Double currentPrice,
            Map<String, Double> derived,
            boolean deep
    ) {
        var key = switch (asset) {
            case "NASDAQ" -> deep ? "NASDAQ_FIB_618" : "NASDAQ_SMA200";
            case "KOSPI" -> deep ? "KOSPI_FIB_618" : "KOSPI_SMA200";
            case "GOLD" -> deep ? "GOLD_FIB_618" : "GOLD_SMA200";
            default -> null;
        };
        if (key == null) return null;
        var level = number(derived, key);
        if (level == null || level <= 0) return null;
        var relation = currentPrice != null && level > currentPrice ? "회복 후 안착" : "지지 확인";
        return new Support(level, key.replace('_', ' ') + " " + relation + " 시 다음 차수 검토");
    }

    private static Timing timing(String asset, CurrentExecutionEvidence evidence) {
        var d = evidence.derivedValues();
        var macro = evidence.regimeScore() >= 55
                && !List.of(
                        "CORRECTION", "RECESSION_RISK", "STAGFLATION",
                        "BOND_VIGILANTE", "STAGFLATION_BOND_VIGILANTE")
                .contains(evidence.regime());
        var sector = switch (asset) {
            case "NASDAQ", "LEVERAGE" -> positive(d, "SECTOR_XLK") || positive(d, "SECTOR_SOXX");
            case "KOSPI" -> greater(d, "SECTOR_SOXX", -5);
            case "SILVER", "COPPER" -> positive(d, "SECTOR_XLI");
            case "EMERGING" -> positive(d, "SECTOR_XLB") || positive(d, "SECTOR_IGF");
            case "GOLD" -> !positive(d, "SECTOR_XLK") || positive(d, "SECTOR_XLU");
            default -> false;
        };
        var flow = switch (asset) {
            case "NASDAQ", "LEVERAGE" -> positive(d, "INSTITUTIONAL_SECTOR_TECH_FLOW");
            case "KOSPI" -> positive(d, "KOSPI_FOREIGN_NET_20D") || positive(d, "KOSPI_FOREIGN_TREND");
            case "EMERGING" -> positive(d, "DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL");
            default -> false;
        };
        var chart = switch (asset) {
            case "NASDAQ", "LEVERAGE" -> greaterOrEqual(d, "NASDAQ_STRUCTURE_SCORE", 60)
                    && !enabled(d, "NASDAQ_FIB_LAST_DEFENSE_BROKEN");
            case "KOSPI" -> greaterOrEqual(d, "KOSPI_STRUCTURE_SCORE", 60)
                    && !enabled(d, "KOSPI_FIB_LAST_DEFENSE_BROKEN");
            case "GOLD" -> enabled(d, "GOLD_ABOVE_200DMA")
                    && greaterOrEqual(d, "GOLD_FIB_SWING_DIRECTION", 0);
            default -> false;
        };
        var overheated = enabled(d, "OVERHEATED")
                || ("NASDAQ".equals(asset) || "LEVERAGE".equals(asset))
                && greater(d, "NASDAQ_DISPARITY", 8);
        var notes = new ArrayList<String>();
        if (!flow) notes.add("독립 수급 확인 전");
        if (!chart) notes.add("가격구조 확인 전");
        if (overheated) notes.add("과열·추격 제한 적용");
        return new Timing(macro, sector, flow, chart, overheated, notes);
    }

    private static String primaryReason(
            CurrentExecutionEvidence.SignalEvidence signal,
            Action action,
            boolean strictBuyNow
    ) {
        var prefix = switch (action) {
            case BUY_NOW -> "적극 매수 조건과 가격구조가 함께 확인됨";
            case SCALE_IN -> strictBuyNow ? "분할 진입" : "매수 우호지만 확인되지 않은 축이 있어 분할 접근";
            case HOLD -> "신규 진입보다 관찰 우선";
            case TAKE_PROFIT -> "목표 비중 축소 검토";
            case EXIT -> "위험 게이트 훼손으로 이탈 검토";
            case AVOID -> "레버리지 허용 조건 미충족";
        };
        var detail = signal.reasons().isEmpty()
                ? signal.unmetReasons().stream().findFirst().orElse("")
                : signal.reasons().getFirst();
        return detail.isBlank() ? prefix : prefix + " · " + detail;
    }

    private static String label(Action action) {
        return switch (action) {
            case BUY_NOW -> "🟢 지금 1차 검토";
            case SCALE_IN -> "🔵 분할 접근";
            case HOLD -> "⚪ 관찰 유지";
            case TAKE_PROFIT -> "🟡 비중 축소 검토";
            case EXIT -> "🔴 이탈 검토";
            case AVOID -> "⚫ 진입 보류";
        };
    }

    private static Double number(Map<String, Double> values, String key) {
        if (key == null) return null;
        var value = values.get(key);
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static boolean positive(Map<String, Double> values, String key) {
        return greater(values, key, 0);
    }

    private static boolean greater(Map<String, Double> values, String key, double expected) {
        var value = number(values, key);
        return value != null && value > expected;
    }

    private static boolean greaterOrEqual(Map<String, Double> values, String key, double expected) {
        var value = number(values, key);
        return value != null && value >= expected;
    }

    private static boolean enabled(Map<String, Double> values, String key) {
        var value = number(values, key);
        return value != null && Double.compare(value, 1d) == 0;
    }

    private record Support(double price, String condition) {
    }
}
