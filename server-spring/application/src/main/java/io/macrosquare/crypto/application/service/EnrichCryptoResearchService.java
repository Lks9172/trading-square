package io.macrosquare.crypto.application.service;

import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomChart;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ChartMarker;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ChartPoint;
import io.macrosquare.crypto.application.model.CryptoResearchModels.DecisionFreshness;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Market;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.port.in.EnrichCryptoResearchUseCase;
import io.macrosquare.crypto.application.port.out.LoadCryptoMarketSeriesPort;
import io.macrosquare.crypto.application.model.CryptoPricePoint;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Updates price-derived crypto fields without replacing moat, on-chain, supply, or narrative evidence. */
public final class EnrichCryptoResearchService implements EnrichCryptoResearchUseCase {

    static final int MAX_MARKET_AGE_DAYS = 2;
    static final int MAX_SUPPORTING_EVIDENCE_AGE_DAYS = 7;
    private final LoadCryptoMarketSeriesPort marketSeries;
    private final Clock clock;

    public EnrichCryptoResearchService(LoadCryptoMarketSeriesPort marketSeries) {
        this(marketSeries, Clock.systemUTC());
    }

    public EnrichCryptoResearchService(LoadCryptoMarketSeriesPort marketSeries, Clock clock) {
        this.marketSeries = Objects.requireNonNull(marketSeries);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Catalog enrich(Catalog baseline) {
        var items = baseline.items().stream().map(this::enrich).toList();
        var freshness = items.stream().map(Research::freshness).filter(Objects::nonNull)
                .min(java.util.Comparator.comparing(DecisionFreshness::eligibleForDecisions))
                .orElse(unknownFreshness());
        var regime = freshness.eligibleForDecisions()
                ? baseline.marketRegime()
                : new io.macrosquare.crypto.application.model.CryptoResearchModels.MarketRegime(
                        baseline.marketRegime().regime(),
                        "관찰 대기",
                        baseline.marketRegime().altRegime(),
                        0,
                        "가격은 최신이지만 코인장 판단용 수급·온체인 보조근거가 오래되어 신규 진입을 보류합니다.",
                        prepend(
                                "보조근거 " + displayDate(freshness.supportingEvidenceObservedOn())
                                        + " · " + displayAge(freshness.supportingEvidenceAgeDays()),
                                baseline.marketRegime().reasons()
                        )
                );
        return new Catalog(items, regime, baseline.assets(), freshness);
    }

    @Override
    public Research enrich(Research baseline) {
        var history = mergedHistory(baseline);
        if (history.isEmpty()) return baseline;
        var latest = history.getLast();
        var closes = history.stream().map(CryptoPricePoint::value).toList();
        var high = closes.stream().mapToDouble(Double::doubleValue).max().orElse(latest.value());
        var low = closes.stream().mapToDouble(Double::doubleValue).min().orElse(latest.value());
        var market = new Market(
                latest.date().toString(), latest.value(), returnAt(history, 7), returnAt(history, 30),
                returnAt(history, 90), baseline.market().volumeTrend30d(), volatility(history, 30),
                percent(latest.value(), high), percent(latest.value(), low)
        );
        var baselineDecisionObservedOn = parseDate(baseline.market().asOf());
        var priceDerivedDecisionCurrent = latest.date().equals(baselineDecisionObservedOn);
        var freshness = freshness(
                latest.date(), baselineDecisionObservedOn, baseline.trendCharts());
        var chart = chart(baseline, history);
        var bottom = baseline.bottomSignal();
        var refreshedBottom = priceDerivedDecisionCurrent
                ? new io.macrosquare.crypto.application.model.CryptoResearchModels.BottomSignal(
                bottom.score(), bottom.state(), bottom.actionBias(), bottom.summary(),
                bottom.volumeConfirmationScore(), bottom.failureRiskScore(), bottom.metrics(), chart,
                bottom.confirmedBottom(), bottom.reasons(), bottom.cautions(), bottom.failureSignals())
                : staleBottom(bottom, chart, latest.date());
        var buyScore = baseline.buyScore();
        var positionSizing = baseline.positionSizing();
        var verdicts = baseline.verdicts();
        var executionBridge = baseline.executionBridge();
        if (!freshness.eligibleForDecisions()) {
            var staleReason = freshness.explanation();
            buyScore = new io.macrosquare.crypto.application.model.CryptoResearchModels.BuyScore(
                    buyScore.appealScore(), buyScore.crowdingScore(), buyScore.buyScore(),
                    "HOLD", "데이터 갱신 대기", prepend(staleReason, buyScore.reasons())
            );
            positionSizing = new io.macrosquare.crypto.application.model.CryptoResearchModels.PositionSizing(
                    0, 0, 100, "보조근거가 최신성 기준을 충족할 때까지 신규 비중을 배정하지 않습니다."
            );
            var oneLiners = verdicts.oneLiners();
            verdicts = new io.macrosquare.crypto.application.model.CryptoResearchModels.Verdicts(
                    verdicts.quality(), "보조근거 갱신 대기", verdicts.valuationProxy(), "HOLD",
                    new io.macrosquare.crypto.application.model.CryptoResearchModels.OneLiners(
                            oneLiners.quality(), "수급·온체인 시계열이 오래되었습니다.", staleReason)
            );
            if (executionBridge != null) {
                executionBridge = new io.macrosquare.crypto.application.model.CryptoResearchModels.ExecutionBridge(
                        executionBridge.asset(), "HOLD", "데이터 갱신 대기", 0,
                        "conflicted", "관찰 대기", staleReason,
                        "현재 가격만으로는 실행하지 않고 수급·온체인 보조근거 갱신을 기다립니다.",
                        prepend(staleReason, executionBridge.timingNotes())
                );
            }
        }
        return new Research(
                baseline.profile(), market, baseline.macro(), baseline.narrative(), baseline.bottomUp(),
                baseline.moat(), baseline.supplyPressure(), baseline.onchain(), baseline.flows(),
                baseline.trendCharts(), freshness, buyScore, refreshedBottom, positionSizing,
                verdicts, baseline.scenarios(), executionBridge
        );
    }

    private DecisionFreshness freshness(LocalDate marketObservedOn,
                                        LocalDate priceDecisionObservedOn,
                                        io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts charts) {
        var asOf = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        var supporting = oldestLatestSupportingDate(charts);
        var marketAge = safeAge(marketObservedOn, asOf);
        var supportingAge = safeAge(supporting, asOf);
        var priceDecisionCurrent = marketObservedOn != null && marketObservedOn.equals(priceDecisionObservedOn);
        var eligible = priceDecisionCurrent
                && marketAge != null && marketAge <= MAX_MARKET_AGE_DAYS
                && supportingAge != null && supportingAge <= MAX_SUPPORTING_EVIDENCE_AGE_DAYS;
        return new DecisionFreshness(
                text(marketObservedOn), text(supporting), marketAge, supportingAge,
                MAX_MARKET_AGE_DAYS, MAX_SUPPORTING_EVIDENCE_AGE_DAYS, eligible,
                eligible ? "CURRENT" : supporting == null ? "UNKNOWN" : "STALE",
                eligible
                        ? "가격과 코인 수급·온체인 보조근거가 모두 최신성 기준을 충족합니다."
                        : !priceDecisionCurrent
                        ? "현재 가격 기준으로 바닥·거래량·매수 점수를 재계산하지 못해 실행 판단과 알림에서 제외합니다."
                        : "가격은 표시하되 오래된 수급·온체인 보조근거는 실행 판단과 알림에서 제외합니다."
        );
    }

    private static io.macrosquare.crypto.application.model.CryptoResearchModels.BottomSignal staleBottom(
            io.macrosquare.crypto.application.model.CryptoResearchModels.BottomSignal source,
            BottomChart chart,
            LocalDate marketObservedOn
    ) {
        var reason = "현재 가격(" + marketObservedOn
                + ") 기준 OHLCV 바닥 계산이 없어 과거 신호를 현재 신호로 사용하지 않습니다.";
        var metrics = source.metrics().stream()
                .map(metric -> new io.macrosquare.crypto.application.model.CryptoResearchModels.BottomMetric(
                        metric.key(), metric.label(), null, "neutral",
                        "현재 OHLCV 재계산 대기 · " + metric.detail()))
                .toList();
        return new io.macrosquare.crypto.application.model.CryptoResearchModels.BottomSignal(
                0, "미충족", "대기", reason,
                null, null, metrics, chart, null,
                List.of(reason), prepend(reason, source.cautions()), source.failureSignals()
        );
    }

    /**
     * Returns the oldest last observation across every required supporting series.
     *
     * <p>Using the newest point from any one series would let a fresh dominance
     * point conceal stale ETF, stablecoin, alt-season, or exchange-flow evidence.
     * A decision is therefore current only when all five inputs are current.</p>
     */
    private static LocalDate oldestLatestSupportingDate(
            io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts charts
    ) {
        var latestDates = java.util.stream.Stream.of(
                        charts.btcDominanceProxy30d(), charts.stablecoinMcap30d(), charts.etfNetFlow30d(),
                        charts.altSeasonProxy30d(), charts.exchangeNetflowProxy30d())
                .map(EnrichCryptoResearchService::latestDate)
                .toList();
        if (latestDates.stream().anyMatch(Objects::isNull)) return null;
        return latestDates.stream().min(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate latestDate(
            List<io.macrosquare.crypto.application.model.CryptoResearchModels.TrendPoint> points
    ) {
        return points.stream()
                .map(point -> parseDate(point.date()))
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    private static Integer safeAge(LocalDate observedOn, LocalDate asOf) {
        if (observedOn == null || observedOn.isAfter(asOf)) return null;
        return Math.toIntExact(ChronoUnit.DAYS.between(observedOn, asOf));
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String text(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String displayDate(String value) {
        return value == null ? "확인 불가" : value;
    }

    private static String displayAge(Integer value) {
        return value == null ? "경과일 불명" : value + "일 경과";
    }

    private static <T> List<T> prepend(T value, List<T> values) {
        var result = new ArrayList<T>(values.size() + 1);
        result.add(value);
        result.addAll(values);
        return List.copyOf(result);
    }

    private static DecisionFreshness unknownFreshness() {
        return new DecisionFreshness(null, null, null, null,
                MAX_MARKET_AGE_DAYS, MAX_SUPPORTING_EVIDENCE_AGE_DAYS,
                false, "UNKNOWN", "코인 의사결정 근거의 최신성을 확인할 수 없습니다.");
    }

    private List<CryptoPricePoint> mergedHistory(Research baseline) {
        var values = new LinkedHashMap<LocalDate, Double>();
        for (var point : baseline.bottomSignal().chart().points()) {
            try {
                var value = point.value() == null ? null : point.value().doubleValue();
                if (value != null && Double.isFinite(value) && value > 0) values.put(LocalDate.parse(point.date()), value);
            } catch (DateTimeParseException ignored) {
                // Malformed historical fallback points are omitted, never exposed to domain calculations.
            }
        }
        for (var point : marketSeries.load(baseline.profile().symbol())) {
            if (point.value() > 0) values.put(point.date(), point.value());
        }
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new CryptoPricePoint(entry.getKey(), entry.getValue())).toList();
    }

    private static BottomChart chart(Research baseline, List<CryptoPricePoint> history) {
        var from = Math.max(0, history.size() - 380);
        var points = history.subList(from, history.size()).stream()
                .map(point -> new ChartPoint(point.date().toString(), point.value())).toList();
        var markers = new ArrayList<>(baseline.bottomSignal().chart().markers().stream()
                .filter(marker -> !"current".equals(marker.kind())).toList());
        var latest = history.getLast();
        markers.add(new ChartMarker("current", latest.date().toString(), latest.value(), "현재"));
        return new BottomChart(points, markers);
    }

    private static Double returnAt(List<CryptoPricePoint> history, int days) {
        var latest = history.getLast();
        var target = latest.date().minusDays(days);
        CryptoPricePoint previous = null;
        for (var point : history) {
            if (!point.date().isAfter(target)) previous = point;
            else break;
        }
        return previous == null ? null : percent(latest.value(), previous.value());
    }

    private static Double volatility(List<CryptoPricePoint> history, int days) {
        var start = Math.max(1, history.size() - days);
        var returns = new ArrayList<Double>();
        for (var index = start; index < history.size(); index++) {
            var previous = history.get(index - 1).value();
            var current = history.get(index).value();
            if (previous > 0 && current > 0) returns.add(Math.log(current / previous));
        }
        if (returns.size() < 2) return null;
        var mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum()
                / (returns.size() - 1);
        return round(Math.sqrt(variance) * Math.sqrt(365) * 100);
    }

    private static Double percent(double current, double previous) {
        if (previous <= 0) return null;
        return round((current / previous - 1) * 100);
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
