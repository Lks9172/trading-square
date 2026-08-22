package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.port.in.RunSectorRotationWalkForwardBacktestUseCase;
import io.macrosquare.research.domain.rotation.SectorWalkForwardBacktest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/research/sectors/backtest")
public final class SectorRotationBacktestController {

    private final RunSectorRotationWalkForwardBacktestUseCase backtest;

    public SectorRotationBacktestController(RunSectorRotationWalkForwardBacktestUseCase backtest) {
        this.backtest = Objects.requireNonNull(backtest);
    }

    @GetMapping("/current")
    public Response current(@RequestParam(name = "years", defaultValue = "7") int years) {
        return Response.from(backtest.run(years));
    }

    public record Response(
            Methodology methodology,
            DateRange dateRange,
            int rebalanceCount,
            double averageMonthlyTurnoverPct,
            Map<String, Horizon> summary,
            ComparisonBaseline comparisonBaseline,
            List<Event> events,
            List<String> warnings
    ) {
        static Response from(SectorWalkForwardBacktest value) {
            var summary = new java.util.LinkedHashMap<String, Horizon>();
            value.horizons().forEach((key, result) -> summary.put(key, Horizon.from(result)));
            return new Response(
                    new Methodology(
                            value.methodologyVersion(),
                            "ADJUSTED_CLOSE_TOTAL_RETURN",
                            "SPY_TR",
                            "최근 1개월 제외 6M·12M 상대 총수익률 각 50%, 252일 상대변동성 조정",
                            "MONTH_END_NO_LOOKAHEAD",
                            true,
                            false,
                            "CURRENT_RISK_ADJUSTED_RELATIVE_MOMENTUM_LAYER_ONLY",
                            "PREDEFINED_INSTITUTIONAL_MOMENTUM_PROXY",
                            List.of(
                                    "https://www.msci.com/indexes/documents/methodology/2_MSCI_Momentum_Indexes_Methodology_20250725.pdf",
                                    "https://www.spglobal.com/spdji/en/documents/methodologies/methodology-sp-momentum-indices.pdf")
                    ),
                    new DateRange(value.from(), value.to()),
                    value.rebalanceCount(),
                    value.averageMonthlyTurnoverPct(),
                    Map.copyOf(summary),
                    ComparisonBaseline.from(value.horizons(), value.comparisonBaselineHorizons()),
                    value.events().stream().map(Event::from).toList(),
                    List.of(
                            "현재 상대강도 레이어는 동일 산식으로 검증했지만 거시·이익추정·수급을 포함한 전체 순환 예측은 아직 완전한 시점별 데이터 검증이 아닙니다.",
                            "운영 점수는 최신 거래일마다 재계산되지만 이 검증은 월말 리밸런스입니다. 월중 순위 변동의 성과는 별도로 검증되지 않았습니다.",
                            "조정주가는 분배금 재투자를 반영한 총수익률 프록시이며 세금·거래비용·ETF 구성 변경은 포함하지 않습니다.",
                            "적중률은 미래 수익 확률이나 매수 신호가 아니라 이후 SPY 초과수익 여부의 과거 비율입니다.",
                            "3·6개월 월별 결과는 서로 겹치므로 독립 표본이 아닙니다. 화면의 주 구간은 중첩 시계열을 Newey-West 방식으로 보정하며, 단순 Wilson 구간도 호환용으로만 함께 제공합니다.",
                            "Top3는 분산 관찰 목록이며 과거 검증상 Top1과 같은 예측력을 가정하면 안 됩니다. 95% 구간과 섹터 동일가중 대비 결과를 함께 확인해야 합니다."
                    )
            );
        }
    }

    public record Methodology(
            String version,
            String dataBasis,
            String benchmark,
            String scoreFormula,
            String evaluationMode,
            boolean liveRelativeStrengthLayerMatched,
            boolean fullRotationForecastValidated,
            String validatedScope,
            String methodologyOrigin,
            List<String> references
    ) {
    }

    public record DateRange(LocalDate from, LocalDate to) {
    }

    public record Horizon(
            int months,
            int sampleCount,
            double top1HitRate,
            double top3HitRate,
            double top1AvgExcessPct,
            double top3AvgExcessPct,
            double top1MedianExcessPct,
            double top3MedianExcessPct,
            double top1PositiveReturnRatePct,
            double top3PositiveReturnRatePct,
            double top1UniverseHitRatePct,
            double top3UniverseHitRatePct,
            double top1AvgUniverseExcessPct,
            double top3AvgUniverseExcessPct,
            double top1HitRate95LowerPct,
            double top1HitRate95UpperPct,
            int overlapAdjustmentLagMonths,
            double top1HitRateOverlapAdjusted95LowerPct,
            double top1HitRateOverlapAdjusted95UpperPct
    ) {
        static Horizon from(SectorWalkForwardBacktest.HorizonResult value) {
            return new Horizon(
                    value.months(), value.sampleCount(), value.top1HitRatePct(), value.top3HitRatePct(),
                    value.top1AverageExcessPct(), value.top3AverageExcessPct(),
                    value.top1MedianExcessPct(), value.top3MedianExcessPct(),
                    value.top1PositiveReturnRatePct(), value.top3PositiveReturnRatePct(),
                    value.top1UniverseHitRatePct(), value.top3UniverseHitRatePct(),
                    value.top1AverageUniverseExcessPct(), value.top3AverageUniverseExcessPct(),
                    value.top1HitRate95LowerPct(), value.top1HitRate95UpperPct(),
                    value.overlapAdjustmentLagMonths(),
                    value.top1HitRateOverlapAdjusted95LowerPct(),
                    value.top1HitRateOverlapAdjusted95UpperPct());
        }
    }

    public record ComparisonBaseline(
            String version,
            String compatibility,
            Map<String, Horizon> summary,
            ComparisonAssessment assessment
    ) {
        static ComparisonBaseline from(
                Map<String, SectorWalkForwardBacktest.HorizonResult> current,
                Map<String, SectorWalkForwardBacktest.HorizonResult> values
        ) {
            var summary = new java.util.LinkedHashMap<String, Horizon>();
            values.forEach((key, value) -> summary.put(key, Horizon.from(value)));
            return new ComparisonBaseline(
                    io.macrosquare.research.domain.rotation.SectorWalkForwardBacktestPolicy
                            .COMPARISON_BASELINE_VERSION,
                    "COMPARISON_ONLY_NOT_LIVE",
                    Map.copyOf(summary),
                    ComparisonAssessment.from(current, values));
        }
    }

    public record ComparisonAssessment(
            String status,
            double threeMonthHitDeltaPct,
            double sixMonthHitDeltaPct,
            double threeMonthAverageExcessDeltaPct,
            double sixMonthAverageExcessDeltaPct
    ) {
        static ComparisonAssessment from(
                Map<String, SectorWalkForwardBacktest.HorizonResult> current,
                Map<String, SectorWalkForwardBacktest.HorizonResult> baseline
        ) {
            var currentThree = current.get("threeMonth");
            var currentSix = current.get("sixMonth");
            var baselineThree = baseline.get("threeMonth");
            var baselineSix = baseline.get("sixMonth");
            if (currentThree == null || currentSix == null || baselineThree == null || baselineSix == null) {
                return new ComparisonAssessment("INSUFFICIENT", 0, 0, 0, 0);
            }
            var threeHit = round(currentThree.top1HitRatePct() - baselineThree.top1HitRatePct());
            var sixHit = round(currentSix.top1HitRatePct() - baselineSix.top1HitRatePct());
            var threeExcess = round(
                    currentThree.top1AverageExcessPct() - baselineThree.top1AverageExcessPct());
            var sixExcess = round(
                    currentSix.top1AverageExcessPct() - baselineSix.top1AverageExcessPct());
            var status = threeExcess >= 0 && sixExcess >= 0 && sixHit >= 0 ? "IMPROVED" : "MIXED";
            return new ComparisonAssessment(status, threeHit, sixHit, threeExcess, sixExcess);
        }

        private static double round(double value) {
            return Math.round(value * 100d) / 100d;
        }
    }

    public record Event(
            LocalDate signalDate,
            String top1,
            List<String> top3,
            Map<String, ForwardResult> forward
    ) {
        static Event from(SectorWalkForwardBacktest.SignalEvent value) {
            var forward = new java.util.LinkedHashMap<String, ForwardResult>();
            value.forward().forEach((key, result) -> forward.put(key, ForwardResult.from(result)));
            return new Event(value.signalDate(), ticker(value.top1()),
                    value.top3().stream().map(Event::ticker).toList(), Map.copyOf(forward));
        }

        private static String ticker(String key) {
            return key.endsWith("_TR") ? key.substring(0, key.length() - 3) : key;
        }
    }

    public record ForwardResult(
            double benchmarkReturnPct,
            double universeReturnPct,
            double top1ReturnPct,
            double top3ReturnPct,
            double top1ExcessPct,
            double top3ExcessPct,
            double top1UniverseExcessPct,
            double top3UniverseExcessPct
    ) {
        static ForwardResult from(SectorWalkForwardBacktest.ForwardResult value) {
            return new ForwardResult(
                    value.benchmarkReturnPct(), value.universeReturnPct(),
                    value.top1ReturnPct(), value.top3ReturnPct(),
                    value.top1ExcessPct(), value.top3ExcessPct(),
                    value.top1UniverseExcessPct(), value.top3UniverseExcessPct());
        }
    }
}
