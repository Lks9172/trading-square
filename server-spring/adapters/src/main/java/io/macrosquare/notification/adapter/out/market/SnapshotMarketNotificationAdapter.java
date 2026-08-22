package io.macrosquare.notification.adapter.out.market;

import io.macrosquare.execution.application.port.in.QueryWeeklyReviewUseCase;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.notification.application.model.MarketNotificationSnapshot;
import io.macrosquare.notification.application.port.out.LoadMarketNotificationPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class SnapshotMarketNotificationAdapter implements LoadMarketNotificationPort {

    private final LoadMarketSnapshotProjectionPort snapshotStore;
    private final QueryWeeklyReviewUseCase weeklyReview;

    public SnapshotMarketNotificationAdapter(
            LoadMarketSnapshotProjectionPort snapshotStore,
            QueryWeeklyReviewUseCase weeklyReview
    ) {
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.weeklyReview = Objects.requireNonNull(weeklyReview);
    }

    @Override
    public MarketNotificationSnapshot loadCurrent() {
        var root = snapshotStore.loadCurrentOrSeed().root();
        var regime = object(root.fields().get("regime"));
        var allocation = object(root.fields().get("allocation"));
        var allocations = object(allocation.fields().get("allocations"));
        var allocationValues = new LinkedHashMap<String, Integer>();
        allocations.fields().forEach((key, value) -> {
            if (value instanceof NumberValue number) allocationValues.put(key, number.value().intValue());
        });
        var signals = new ArrayList<MarketNotificationSnapshot.Signal>();
        if (root.fields().get("signals") instanceof ArrayValue array) {
            for (var value : array.values()) {
                var signal = object(value);
                var asset = text(signal.fields().get("asset"), "");
                if (asset.isBlank()) continue;
                var conditionsTotal = integer(signal.fields().get("conditionsTotal"), 0);
                signals.add(new MarketNotificationSnapshot.Signal(
                        asset,
                        text(signal.fields().get("signal"), "HOLD"),
                        integer(signal.fields().get("conditionsMet"), 0),
                        conditionsTotal,
                        legacyCompatibleCoverage(signal, conditionsTotal),
                        firstConstraint(signal.fields().get("unmetReasons"))
                ));
            }
        }
        var meta = object(root.fields().get("meta"));
        var confirmationLines = new ArrayList<>(breadth(meta));
        confirmationLines.addAll(collectionHealth(meta));
        confirmationLines.addAll(inputFreshness(meta));
        var derived = object(root.fields().get("derived"));
        confirmationLines.addAll(macdTiming(derived));
        confirmationLines.addAll(liquidity(derived));
        return new MarketNotificationSnapshot(
                text(root.fields().get("timestamp"), ""),
                text(regime.fields().get("regime"), "UNKNOWN"),
                integer(regime.fields().get("score"), 0),
                signals,
                allocationValues,
                bool(allocation.fields().get("leverageAllowed"), false),
                confirmationLines
        );
    }

    @Override
    public String loadWeeklyReportText() {
        return weeklyReview.review().text();
    }

    private static List<String> breadth(ObjectValue meta) {
        var gate = object(meta.fields().get("marketBreadthGate"));
        if (!(gate.fields().get("markets") instanceof ArrayValue markets)) return List.of();
        var values = new ArrayList<String>();
        for (var item : markets.values()) {
            var market = object(item);
            var asset = text(market.fields().get("asset"), "MARKET");
            var status = text(market.fields().get("status"), "OFF");
            var date = text(market.fields().get("signalDate"), "");
            var line = "   • " + ("SP500".equals(asset) ? "S&P500" : asset) + " 반전신호: " + status;
            if (!date.isBlank()) line += " / 최근 " + date;
            values.add(line);
        }
        return List.copyOf(values);
    }

    private static List<String> liquidity(ObjectValue derived) {
        var signal = indicatorValue(derived, "LIQUIDITY_PLUMBING_SIGNAL");
        if (signal == null) return List.of();
        var bullish = indicatorValue(derived, "LIQUIDITY_PLUMBING_BULLISH_AXES");
        var bearish = indicatorValue(derived, "LIQUIDITY_PLUMBING_BEARISH_AXES");
        var neutral = indicatorValue(derived, "LIQUIDITY_PLUMBING_NEUTRAL_AXES");
        var confidence = indicatorValue(derived, "LIQUIDITY_PLUMBING_CONFIDENCE");
        var values = new ArrayList<String>();
        var direction = signal >= 1.5 ? "전축 공급"
                : signal >= .5 ? "공급 우위"
                : signal <= -1.5 ? "전축 흡수"
                : signal <= -.5 ? "흡수 우위" : "혼조";
        var line = "   • 현재 유동성 3축: " + direction + " (" + signed(signal) + ')';
        if (bullish != null && bearish != null) {
            line += " / 공급 " + bullish.intValue() + "·흡수 " + bearish.intValue();
            if (neutral != null) line += "·중립 " + neutral.intValue();
        }
        if (confidence != null) line += " / 데이터 " + confidence.intValue() + '%';
        values.add(line);

        var reserveLevel = indicatorValue(derived, "WRESBAL_LEVEL_TN");
        if (reserveLevel != null) {
            values.add("   • 은행 준비금: " + String.format(java.util.Locale.ROOT, "%.2fT", reserveLevel)
                    + (reserveLevel < 3 ? " / ⚠ 3T 모니터링선 아래" : " / 3T 모니터링선 이상")
                    + " (공식 안전선·수익 신호 아님)");
        }

        var tgaContribution = indicatorValue(derived, "TGA_LIQUIDITY_CONTRIBUTION_4W_BN");
        var rrpContribution = indicatorValue(derived, "RRP_LIQUIDITY_CONTRIBUTION_4W_BN");
        if (tgaContribution != null || rrpContribution != null) {
            values.add("   • 4주 배관 기여: TGA "
                    + (tgaContribution == null ? "자료 부족" : signedNumber(tgaContribution) + "B")
                    + " / ON RRP "
                    + (rrpContribution == null ? "자료 부족" : signedNumber(rrpContribution) + "B")
                    + " (위험자산 직접 순유입 아님)");
        }

        var impulse = indicatorValue(derived, "NET_LIQUIDITY_IMPULSE_4W_BN");
        var impulseState = indicatorValue(derived, "NET_LIQUIDITY_IMPULSE_STATE");
        var turn = indicatorValue(derived, "NET_LIQUIDITY_TURN_SIGNAL");
        var acceleration = indicatorValue(derived, "NET_LIQUIDITY_ACCELERATION_4W_BN");
        if (impulse != null) {
            var impulseLabel = impulseState == null ? "상태 자료 부족"
                    : impulseState >= 1.5 ? "강한 확장" : impulseState >= .5 ? "확장"
                    : impulseState <= -1.5 ? "강한 흡수" : impulseState <= -.5 ? "흡수" : "방향 혼조";
            var impulseLine = "   • 미국 순유동성 4주: " + impulseLabel + " / " + signedNumber(impulse) + "B";
            if (acceleration != null) impulseLine += " / 가속 " + signedNumber(acceleration) + "B";
            if (turn != null && turn != 0) impulseLine += turn > 0 ? " / 4주 구간 확장 전환 ON" : " / 4주 구간 흡수 전환 ON";
            values.add(impulseLine);
        }

        var transmission = indicatorValue(derived, "LIQUIDITY_TRANSMISSION_STRESS_SCORE");
        var transmissionCoverage = indicatorValue(derived, "LIQUIDITY_TRANSMISSION_COVERAGE");
        var tgaOffset = indicatorValue(derived, "TGA_LAGGED_ISSUANCE_CONTEXT");
        var tgaContextKey = "TGA_LAGGED_ISSUANCE_CONTEXT";
        if (tgaOffset == null) {
            tgaContextKey = "TGA_ISSUANCE_OFFSET_RISK";
            tgaOffset = indicatorValue(derived, tgaContextKey);
        }
        var rrpLow = indicatorValue(derived, "RRP_BUFFER_LOW");
        if (transmissionCoverage == null || transmissionCoverage < 67) {
            values.add("   • ⚠ 유동성 전달 데이터 부족: 3축 중 최소 2축 확인 전 저스트레스로 해석하지 않음");
        } else if (transmission != null && transmission >= 2) {
            values.add("   • ⚠ 유동성 전달 스트레스 " + transmission.intValue() + "/3: 공급만으로 위험자산 반응을 확정하지 않음");
        }
        if (tgaOffset != null && tgaOffset >= 1) {
            var sourceDate = indicatorDate(derived, "TREASURY_ISSUANCE_DIRECTION");
            if (sourceDate.isBlank()) sourceDate = indicatorDate(derived, tgaContextKey);
            values.add("   • ⚠ TGA 감소와 최신 공표 분기 국채 순거래 확대 동반"
                    + (sourceDate.isBlank() ? "" : " (분기 기준 " + sourceDate + ")")
                    + ": 후행 맥락이며 재충전·경매 예측 아님");
        }
        if (rrpLow != null && rrpLow >= 1) {
            values.add("   • ⚠ ON RRP 저잔액: 잔액 기준 추가 감소 여지 제한");
        }

        var spillover = indicatorValue(derived, "DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL");
        var spilloverConfidence = indicatorValue(derived, "DOLLAR_LIQUIDITY_SPILLOVER_CONFIDENCE");
        if (spillover != null) {
            var spilloverLabel = spillover >= 1.5 ? "한국·신흥국 전이 강함"
                    : spillover >= .5 ? "전이 우호"
                    : spillover <= -1.5 ? "자금 회수 강함"
                    : spillover <= -.5 ? "자금 회수 압력" : "전이 혼조";
            var spilloverLine = "   • 달러→신흥국: " + spilloverLabel + " (" + signed(spillover) + ')';
            if (spilloverConfidence != null) spilloverLine += " / 데이터 " + spilloverConfidence.intValue() + '%';
            values.add(spilloverLine);
        }
        return List.copyOf(values);
    }

    private static List<String> macdTiming(ObjectValue derived) {
        var values = new ArrayList<String>();
        for (var asset : List.of("SP500", "NASDAQ")) {
            var label = "SP500".equals(asset) ? "S&P500" : "NASDAQ";
            var daily = macdTimeframe(derived, asset, "", "일");
            var weekly = macdTimeframe(derived, asset, "WEEKLY_", "주");
            if (daily != null) values.add("   • " + label + " MACD 일봉: " + daily);
            if (weekly != null) {
                var provisional = indicatorValue(derived, asset + "_MACD_CURRENT_WEEK_PROVISIONAL");
                values.add("   • " + label + " MACD 주봉: " + weekly
                        + (provisional != null && provisional >= .5 ? " · 이번 주 진행 중" : ""));
            }
        }
        if (!values.isEmpty()) {
            values.add("   · MACD/다이버전스는 후행 보조지표이며 단독 매수·매도 신호가 아님");
        }
        return List.copyOf(values);
    }

    private static String macdTimeframe(ObjectValue derived, String asset, String timeframe, String unit) {
        var key = asset + "_" + timeframe + "MACD_";
        var position = indicatorValue(derived, key + "POSITION");
        var cross = indicatorValue(derived, key + "CROSS");
        var histogram = indicatorValue(derived, key + "HISTOGRAM_STATE");
        var divergence = indicatorValue(derived, key + "DIVERGENCE");
        if (position == null || cross == null || histogram == null || divergence == null) return null;
        var crossAge = indicatorValue(derived, key + "CROSS_AGE");
        var divergenceAge = indicatorValue(derived, key + "DIVERGENCE_AGE");
        var divergenceActive = indicatorValue(derived, key + "DIVERGENCE_ACTIVE");
        var date = indicatorDate(derived, key + "POSITION");
        var fields = new ArrayList<String>();
        fields.add(marketCrossLabel(cross, crossAge, unit));
        fields.add(position > .5 ? "시그널 위" : position < -.5 ? "시그널 아래" : "시그널선 접점");
        fields.add(marketHistogramLabel(histogram));
        fields.add(marketDivergenceLabel(divergence, divergenceActive, divergenceAge, unit));
        return (date.isBlank() ? "" : date + " 기준 · ") + String.join(" · ", fields);
    }

    private static String marketCrossLabel(double cross, Double age, String unit) {
        var label = cross > .5 ? "상방 골든크로스" : cross < -.5 ? "하방 데드크로스" : "교차 없음";
        return age == null || Math.abs(cross) <= .5 ? label : label + "(" + age.intValue() + unit + " 전)";
    }

    private static String marketHistogramLabel(double value) {
        if (value >= 1.5) return "양(확대)";
        if (value >= .5) return "음(축소)";
        if (value <= -1.5) return "음(확대)";
        if (value <= -.5) return "양(둔화)";
        return "히스토그램 보합";
    }

    private static String marketDivergenceLabel(
            double divergence,
            Double active,
            Double age,
            String unit
    ) {
        if (Math.abs(divergence) <= .5) return "다이버전스 없음";
        var direction = divergence > 0 ? "상승" : "하락";
        var ageLabel = age == null ? "" : "(" + age.intValue() + unit + " 전)";
        return active != null && active >= .5
                ? direction + " 다이버전스 ON" + ageLabel
                : "과거 " + direction + " 다이버전스" + ageLabel;
    }

    private static String signedNumber(double value) {
        return (value > 0 ? "+" : "") + Math.round(value);
    }

    private static List<String> collectionHealth(ObjectValue meta) {
        var health = object(meta.fields().get("collectionHealth"));
        var status = text(health.fields().get("status"), "UNKNOWN");
        if ("HEALTHY".equals(status) || "UNKNOWN".equals(status)) return List.of();
        if ("UNAVAILABLE".equals(status)) {
            return List.of("   • 수집 진단: 상태 원장 확인 불가 / 값의 날짜 신선도만 적용");
        }
        var sources = object(health.fields().get("sources"));
        var lines = new ArrayList<String>();
        sources.fields().forEach((source, value) -> {
            var row = object(value);
            var sourceStatus = text(row.fields().get("status"), "UNKNOWN");
            if ("SUCCESS".equals(sourceStatus)) return;
            var failures = texts(row.fields().get("failureKeys"));
            var label = switch (source) {
                case "FEAR_GREED" -> "공포·탐욕";
                case "SENTIMENT" -> "심리";
                case "STABLECOIN" -> "스테이블코인";
                case "YAHOO" -> "가격";
                case "FRED" -> "거시";
                case "KRX" -> "국내 수급";
                default -> source;
            };
            var state = "FAILED".equals(sourceStatus) ? "실패"
                    : "STALE".equals(sourceStatus) ? "실행 지연"
                    : "LIMITED".equals(sourceStatus) ? "공급자 정책 제한" : "부분 성공";
            var line = "   • 수집 " + label + ": " + state;
            if (!failures.isEmpty()) line += " / 결측 " + String.join(", ", failures);
            lines.add(line);
        });
        if (lines.isEmpty()) lines.add("   • 수집 진단: " + status);
        return List.copyOf(lines);
    }

    private static List<String> inputFreshness(ObjectValue meta) {
        var freshness = object(meta.fields().get("inputFreshness"));
        var raw = integer(freshness.fields().get("rawExcluded"), 0);
        var derived = integer(freshness.fields().get("derivedExcluded"), 0);
        if (raw + derived == 0) return List.of();
        return List.of("   • 신호 신선도: " + (raw + derived) + "개 산식 제외"
                + " (원천 " + raw + "·파생 " + derived + ")");
    }

    private static Double indicatorValue(ObjectValue derived, String key) {
        var indicator = object(derived.fields().get(key));
        if (indicator.fields().get("eligibleForSignals") instanceof BooleanValue eligible && !eligible.value()) {
            return null;
        }
        return indicator.fields().get("value") instanceof NumberValue number
                ? number.value().doubleValue() : null;
    }

    private static String indicatorDate(ObjectValue derived, String key) {
        var indicator = object(derived.fields().get(key));
        return text(indicator.fields().get("date"), "");
    }

    private static String signed(double value) {
        return value > 0 ? "+" + (int) Math.round(value) : Integer.toString((int) Math.round(value));
    }

    private static ObjectValue object(StructuredValue value) {
        return value instanceof ObjectValue object ? object : new ObjectValue(java.util.Map.of());
    }

    private static String text(StructuredValue value, String fallback) {
        return value instanceof TextValue text ? text.value() : fallback;
    }

    private static int integer(StructuredValue value, int fallback) {
        return value instanceof NumberValue number ? number.value().intValue() : fallback;
    }

    private static int legacyCompatibleCoverage(ObjectValue signal, int conditionsTotal) {
        var value = signal.fields().get("dataCoveragePct");
        if (value instanceof NumberValue number) return number.value().intValue();
        return conditionsTotal > 0 ? 100 : 0;
    }

    private static String firstConstraint(StructuredValue source) {
        return texts(source).stream().filter(value -> value.startsWith("⚠")).findFirst().orElse("");
    }

    private static List<String> texts(StructuredValue source) {
        if (!(source instanceof ArrayValue values)) return List.of();
        return values.values().stream()
                .filter(TextValue.class::isInstance)
                .map(TextValue.class::cast)
                .map(TextValue::value)
                .toList();
    }

    private static boolean bool(StructuredValue value, boolean fallback) {
        return value instanceof BooleanValue bool ? bool.value() : fallback;
    }
}
