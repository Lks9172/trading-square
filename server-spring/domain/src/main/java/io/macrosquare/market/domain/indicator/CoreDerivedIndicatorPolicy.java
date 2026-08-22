package io.macrosquare.market.domain.indicator;

import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis;
import io.macrosquare.technical.domain.MacdSignalAnalysis;
import io.macrosquare.technical.domain.MacdSignalPolicy;
import io.macrosquare.technical.domain.TechnicalClosePoint;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-independent calculations that keep the live regime, sector rotation and
 * timing decisions current after the Node runtime is removed. Long-tail research
 * indicators remain in the persisted projection with their original observation date.
 */
public final class CoreDerivedIndicatorPolicy {

    private static final MarketInputFreshnessPolicy INPUT_FRESHNESS = new MarketInputFreshnessPolicy();

    /** Mutually exclusive GICS sector sleeves used by the live rotation model and its backtest. */
    private static final List<String> STANDARD_SECTOR_KEYS = List.of(
            "XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB",
            "XLRE", "XLU", "XLP"
    );
    /** Overlapping strategic-theme ETFs. They must never alter a standard sector percentile. */
    private static final List<String> STRATEGIC_THEME_KEYS = List.of(
            "SOXX", "SMH", "ITA", "GRID", "IGF"
    );
    private static final List<String> SECTOR_KEYS = java.util.stream.Stream.concat(
            STANDARD_SECTOR_KEYS.stream(), STRATEGIC_THEME_KEYS.stream()).toList();
    private final MacdSignalPolicy macdSignalPolicy;

    public CoreDerivedIndicatorPolicy() {
        this(new MacdSignalPolicy());
    }

    public CoreDerivedIndicatorPolicy(MacdSignalPolicy macdSignalPolicy) {
        this.macdSignalPolicy = java.util.Objects.requireNonNull(macdSignalPolicy);
    }

    public Map<String, CoreDerivedIndicator> evaluate(
            Map<String, Double> raw,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var out = new LinkedHashMap<String, CoreDerivedIndicator>();
        ratio(out, asOf, "REAL_YIELD", "real_yield", raw.get("DGS10"), raw.get("T10YIE"), false,
                "DGS10 - T10YIE");
        ratio(out, asOf, "GOLD_SILVER_RATIO", "gold_silver_ratio", raw.get("GOLD"), raw.get("SILVER"), true,
                "GOLD / SILVER");
        ratio(out, asOf, "COPPER_GOLD_RATIO", "copper_gold_ratio", raw.get("COPPER"), raw.get("GOLD"), true,
                "COPPER / GOLD");
        ratio(out, asOf, "GOLD_COPPER_RATIO", "gold_copper_ratio", raw.get("GOLD"), raw.get("COPPER"), true,
                "GOLD / COPPER");
        difference(out, asOf, "SOFR_EFFR_SPREAD", "sofr_effr_spread", raw.get("SOFR"), raw.get("EFFR"),
                "SOFR - EFFR");
        difference(out, asOf, "SOFR_IORB_SPREAD", "sofr_iorb_spread", raw.get("SOFR"), raw.get("IORB"),
                "SOFR - IORB (positive means reserve-market pressure)");

        var m2Yoy = yearOverYear(history(histories, "FRED:M2SL"));
        put(out, asOf, "US_M2_YOY", "us_m2_yoy", m2Yoy, "M2SL latest / one-year-prior - 1 (%)");
        m2Impulse(out, histories, asOf);
        if (m2Yoy != null && m2Yoy >= -20 && m2Yoy <= 30) {
            put(out, asOf, "GLOBAL_M2_PROXY", "global_m2_proxy", m2Yoy,
                    "US M2 YoY proxy; stale external M3 series excluded");
        }
        var cpiYoy = yearOverYear(history(histories, "FRED:CPI"));
        var pceYoy = yearOverYear(history(histories, "FRED:PCE"));
        put(out, asOf, "CPI_YOY", "cpi_yoy", cpiYoy, "CPI latest / one-year-prior - 1 (%)");
        put(out, asOf, "PCE_YOY", "pce_yoy", pceYoy, "PCE latest / one-year-prior - 1 (%)");

        direction(out, histories, "RRP_DIRECTION", "rrp_direction", "FRED:RRPONTSYD", 5, 5,
                "RRP recent 5-point average vs prior 5-point average (%)");
        direction(out, histories, "TGA_DIRECTION", "tga_direction", "FRED:WDTGAL", 2, 2,
                "Wednesday-level TGA recent 2-point average vs prior 2-point average (%)");
        direction(out, histories, "MMF_DIRECTION", "mmf_direction", "FRED:WRMFNS", 2, 2,
                "MMF recent 2-point average vs prior 2-point average (%)");
        direction(out, histories, "WRESBAL_DIRECTION", "wresbal_direction", "FRED:WRESBAL", 2, 2,
                "Reserve balances recent 2-point average vs prior 2-point average (%)");
        reserveBalanceCushion(out, histories);
        treasuryIssuancePressure(out, histories);
        netLiquidityImpulse(out, histories);
        put(out, asOf, "LIQUIDITY_DIRECTION", "liquidity_direction", liquidityDirection(out),
                "Net-liquidity impulse state when available; legacy RRP/TGA/MMF/reserve/M2 fallback (-2..2)");
        liquidityPlumbing(out, asOf);

        trendDifference(out, histories, asOf, "DXY_TREND", "dxy_trend", "YAHOO:DXY", 5, 15, 5,
                "DXY recent 5-point average - 15~20-point-prior average");
        trendDifference(out, histories, asOf, "DXY_TREND_LONG", "dxy_trend_long", "YAHOO:DXY", 10, 50, 10,
                "DXY recent 10-point average - 50~60-point-prior average");
        realYieldTrend(out, histories, asOf);

        indexTechnicals(out, asOf, "NASDAQ", history(histories, "YAHOO:NASDAQ"));
        indexTechnicals(out, asOf, "KOSPI", history(histories, "YAHOO:KOSPI"));
        assetTechnicals(out, asOf, "GOLD", history(histories, "YAHOO:GOLD"));
        marketPriceStructure(out, asOf, "BTC", history(histories, "YAHOO:BTC"));
        assetMomentum(out, asOf, "BTC", history(histories, "YAHOO:BTC"), 20);
        for (var asset : java.util.stream.Stream.concat(
                List.of(
                        "SP500", "NASDAQ", "KOSPI", "KOSDAQ", "GOLD", "SILVER", "COPPER", "WTI",
                        "DXY", "USDKRW", "USDJPY", "BTC", "ETH", "SOL", "XRP", "BNB").stream(),
                SECTOR_KEYS.stream()).distinct().toList()) {
            macdTechnicals(out, asset, history(histories, "YAHOO:" + asset));
        }
        oilInflationPressure(out, histories, asOf);
        krxInvestorFlow(out, raw, histories);
        liquiditySpillover(out, raw, asOf);
        sentiment(out, raw, histories, asOf);

        sectorMomentum(out, histories, asOf);
        credit(out, histories, raw, asOf);
        liquidityTransmission(out, raw, asOf);
        copperGoldTrend(out, histories, asOf);
        tiers(out, raw, asOf);
        goldilocks(out, raw, cpiYoy, pceYoy, histories, asOf);
        flags(out, raw, histories, asOf);
        return Map.copyOf(out);
    }

    private void macdTechnicals(
            Map<String, CoreDerivedIndicator> out,
            String prefix,
            List<MarketSeriesPoint> points
    ) {
        if (points.isEmpty()) return;
        var analysis = macdSignalPolicy.evaluate(points.stream()
                .map(point -> new TechnicalClosePoint(point.date(), point.value()))
                .toList());
        putMacd(out, prefix, "", analysis.daily());
        putMacd(out, prefix, "WEEKLY_", analysis.weekly());
        put(out, analysis.daily().asOf(), prefix + "_MACD_CURRENT_WEEK_PROVISIONAL",
                prefix.toLowerCase() + "_macd_current_week_provisional",
                analysis.currentWeekProvisional() ? 1d : 0d,
                "1=latest weekly MACD bar is still provisional; 0=latest close is Friday-or-later");
    }

    private static void putMacd(
            Map<String, CoreDerivedIndicator> out,
            String prefix,
            String timeframe,
            MacdSignalAnalysis value
    ) {
        var key = prefix + "_" + timeframe + "MACD_";
        var name = prefix.toLowerCase() + "_" + timeframe.toLowerCase() + "macd_";
        put(out, value.asOf(), key + "LINE", name + "line", value.macd(),
                "EMA12(close)-EMA26(close)");
        put(out, value.asOf(), key + "SIGNAL", name + "signal", value.signal(),
                "EMA9(MACD line)");
        put(out, value.asOf(), key + "HISTOGRAM", name + "histogram", value.histogram(),
                "MACD line-signal line");
        put(out, value.asOf(), key + "POSITION", name + "position", switch (value.position()) {
            case ABOVE_SIGNAL -> 1d;
            case BELOW_SIGNAL -> -1d;
            case AT_SIGNAL -> 0d;
            case UNAVAILABLE -> null;
        }, "+1=above signal, -1=below signal, 0=at signal");
        put(out, value.asOf(), key + "ZERO_REGIME", name + "zero_regime", switch (value.zeroRegime()) {
            case ABOVE_ZERO -> 1d;
            case BELOW_ZERO -> -1d;
            case AT_ZERO -> 0d;
            case UNAVAILABLE -> null;
        }, "+1=MACD above zero, -1=below zero, 0=at zero");
        put(out, value.asOf(), key + "CROSS", name + "cross", switch (value.latestCross()) {
            case BULLISH_CROSS -> 1d;
            case BEARISH_CROSS -> -1d;
            case NONE -> 0d;
            case UNAVAILABLE -> null;
        }, "+1=latest signal-line cross bullish(golden), -1=bearish(dead), 0=no observed cross");
        put(out, value.asOf(), key + "CROSS_AGE", name + "cross_age",
                value.sessionsSinceCross() == null ? null : value.sessionsSinceCross().doubleValue(),
                "trading observations since latest MACD signal-line cross");
        put(out, value.asOf(), key + "HISTOGRAM_STATE", name + "histogram_state", switch (value.histogramState()) {
            case EXPANDING_POSITIVE -> 2d;
            case CONTRACTING_NEGATIVE -> 1d;
            case FLAT -> 0d;
            case CONTRACTING_POSITIVE -> -1d;
            case EXPANDING_NEGATIVE -> -2d;
            case UNAVAILABLE -> null;
        }, "+2=positive expanding, +1=negative contracting, -1=positive contracting, -2=negative expanding");
        put(out, value.asOf(), key + "DIVERGENCE", name + "divergence", switch (value.divergence()) {
            case BULLISH -> 1d;
            case BEARISH -> -1d;
            case NONE -> 0d;
            case UNAVAILABLE -> null;
        }, "+1=regular bullish, -1=regular bearish; confirmed close pivots only");
        put(out, value.asOf(), key + "DIVERGENCE_ACTIVE", name + "divergence_active",
                value.divergenceActive() ? 1d : 0d,
                "1=latest confirmed divergence remains within freshness window");
        put(out, value.asOf(), key + "DIVERGENCE_AGE", name + "divergence_age",
                value.sessionsSinceDivergence() == null ? null : value.sessionsSinceDivergence().doubleValue(),
                "observations since right-side pivot confirmation; no look-ahead");
    }

    private static void sentiment(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var putCall = distinctDates(history(histories, "SENTIMENT:PC_RATIO"));
        Double putCall10d = null;
        if (putCall.size() >= 10) {
            putCall10d = averageLastPoints(putCall, 10);
            put(out, putCall.getLast().date(), "PC_RATIO_10D", "pc_ratio_10d", putCall10d,
                    "Mean of the latest ten distinct daily CBOE-chain SPX/SPY/QQQ put/call volume ratios");
        }
        put(out, asOf, "PC_RATIO_HISTORY_COUNT", "pc_ratio_history_count", (double) putCall.size(),
                "Number of distinct daily SPX/SPY/QQQ put/call observations available");
        if (putCall.size() >= 60) {
            var rolling = new ArrayList<Double>();
            for (var end = 9; end < putCall.size(); end++) {
                double sum = 0;
                for (var index = end - 9; index <= end; index++) sum += putCall.get(index).value();
                rolling.add(sum / 10d);
            }
            var history = rolling.size() > 252 ? rolling.subList(rolling.size() - 252, rolling.size()) : rolling;
            var current = rolling.getLast();
            var belowOrEqual = history.stream().filter(value -> value <= current).count();
            put(out, putCall.getLast().date(), "PC_RATIO_10D_PERCENTILE", "pc_ratio_10d_percentile",
                    belowOrEqual * 100d / history.size(),
                    "Latest 10D basket put/call ratio percentile within up to 252 rolling observations; not return probability");
        }

        var components = new ArrayList<Double>();
        addFinite(components, normalize(raw.get("FEAR_GREED"), 0, 100));
        addFinite(components, invert(putCall10d, .70, 1.20));
        addFinite(components, normalize(raw.get("AAII_BULL_BEAR_SPREAD"), -20, 20));
        addFinite(components, normalize(raw.get("NAAIM_EXPOSURE"), 30, 70));
        put(out, asOf, "PSYCH_SUBSCORE_COVERAGE", "psych_subscore_coverage",
                components.size() * 25d,
                "Available Fear & Greed / 10D basket put-call / AAII / NAAIM components divided by four");
        if (components.size() >= 2) {
            put(out, asOf, "PSYCH_SUBSCORE", "psych_subscore",
                    components.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    "Available-component mean on 0=fear to 1=greed scale; condition composite, not return probability");
        }
    }

    private static List<MarketSeriesPoint> distinctDates(List<MarketSeriesPoint> source) {
        var values = new LinkedHashMap<LocalDate, MarketSeriesPoint>();
        source.stream().sorted(Comparator.comparing(MarketSeriesPoint::date))
                .forEach(point -> values.put(point.date(), point));
        return List.copyOf(values.values());
    }

    private static double averageLastPoints(List<MarketSeriesPoint> values, int count) {
        return values.subList(values.size() - count, values.size()).stream()
                .mapToDouble(MarketSeriesPoint::value).average().orElseThrow();
    }

    private static void addFinite(List<Double> target, Double value) {
        if (value != null && Double.isFinite(value)) target.add(value);
    }

    private static Double normalize(Double value, double low, double high) {
        if (value == null || !Double.isFinite(value) || high <= low) return null;
        return clamp((value - low) / (high - low), 0, 1);
    }

    private static Double invert(Double value, double greedy, double fearful) {
        var normalized = normalize(value, greedy, fearful);
        return normalized == null ? null : 1 - normalized;
    }

    private static void krxInvestorFlow(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            Map<String, List<MarketSeriesPoint>> histories
    ) {
        var foreign = history(histories, "KRX:KOSPI_FOREIGN_NET_1D");
        if (foreign.isEmpty()) return;
        var observationDate = foreign.getLast().date();
        var latestForeign = foreign.getLast().value();
        put(out, observationDate, "KOSPI_FOREIGN_NET_1D", "kospi_foreign_net_1d", latestForeign,
                "KOSPI foreign net purchase for the latest trading day (KRW 100M, Naver Finance KRX aggregate)");

        var buyStreak = streak(foreign, true);
        var sellStreak = streak(foreign, false);
        put(out, observationDate, "KOSPI_FOREIGN_BUY_STREAK", "kospi_foreign_buy_streak", (double) buyStreak,
                "Consecutive positive foreign-flow trading days counted backward from latest");
        put(out, observationDate, "KOSPI_FOREIGN_SELL_STREAK", "kospi_foreign_sell_streak", (double) sellStreak,
                "Consecutive negative foreign-flow trading days counted backward from latest");
        put(out, observationDate, "KOSPI_FOREIGN_STREAK_DAYS", "kospi_foreign_streak_days",
                buyStreak > 0 ? (double) buyStreak : -1d * sellStreak,
                "Signed foreign-flow streak: positive=net buying, negative=net selling");

        var historicOneDay = latestForeign <= -70_000 ? -2d
                : latestForeign <= -30_000 ? -1d
                : latestForeign >= 70_000 ? 2d
                : latestForeign >= 30_000 ? 1d : 0d;
        put(out, observationDate, "KOSPI_FOREIGN_NET_1D_HISTORIC_FLAG",
                "kospi_foreign_net_1d_historic_flag", historicOneDay,
                "Latest foreign flow: +/-3T KRW warning and +/-7T KRW historic threshold");

        var fx = raw.get("USDKRW");
        if (fx != null) {
            var combo = fx >= 1_500 && sellStreak >= 5 ? 2d
                    : fx >= 1_480 && sellStreak >= 3 ? 1d
                    : fx <= 1_480 && sellStreak == 0 ? -1d : 0d;
            put(out, observationDate, "FX_FOREIGN_COMBO_ALERT", "fx_foreign_combo_alert", combo,
                    "2=USDKRW>=1500 and sell streak>=5; 1=>=1480 and >=3; -1=<=1480 with no sell streak; else 0");
        }

        if (foreign.size() >= 5) {
            var net5 = sumLast(foreign, 5);
            put(out, observationDate, "KOSPI_FOREIGN_NET_5D", "kospi_foreign_net_5d", net5,
                    "Latest five trading-day foreign net-purchase sum (KRW 100M)");

            var institution = history(histories, "KRX:KOSPI_INSTITUTION_NET_1D");
            if (institution.size() >= 5) {
                put(out, observationDate, "KOSPI_INSTITUTION_NET_5D", "kospi_institution_net_5d",
                        sumLast(institution, 5), "Latest five trading-day institutional net-purchase sum (KRW 100M)");
            }

            var individual = history(histories, "KRX:KOSPI_INDIVIDUAL_NET_1D");
            if (!individual.isEmpty()) {
                put(out, observationDate, "KOSPI_INDIVIDUAL_NET_1D", "kospi_individual_net_1d",
                        individual.getLast().value(), "Latest individual net purchase (KRW 100M)");
            }
            if (individual.size() >= 5) {
                var individual5 = sumLast(individual, 5);
                put(out, observationDate, "KOSPI_INDIVIDUAL_NET_5D", "kospi_individual_net_5d",
                        individual5, "Latest five trading-day individual net-purchase sum (KRW 100M)");
                var divergence = net5 <= -30_000 && individual5 >= 30_000 ? 1d
                        : net5 >= 30_000 && individual5 <= -30_000 ? -1d : 0d;
                put(out, observationDate, "KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE",
                        "kospi_foreign_individual_divergence", divergence,
                        "+1=individuals absorb >=3T KRW foreign selling; -1=the reverse; else 0");
            }

            var pension = history(histories, "KRX:KOSPI_PENSION_NET_1D");
            if (pension.size() >= 5) {
                var pension5 = sumLast(pension, 5);
                put(out, observationDate, "KOSPI_PENSION_NET_5D", "kospi_pension_net_5d", pension5,
                        "Latest five trading-day pension-fund net-purchase sum (KRW 100M)");
                put(out, observationDate, "KRX_PENSION_FUND_FLOW", "krx_pension_fund_flow",
                        pension5 >= 10_000 ? 1d : pension5 <= -10_000 ? -1d : 0d,
                        "+1=pension 5D >=+1T KRW; -1=<=-1T KRW; else 0");
            }

            var fxHistory = history(histories, "YAHOO:USDKRW");
            if (fxHistory.size() >= 5) {
                var fxStable = fxHistory.subList(fxHistory.size() - 5, fxHistory.size()).stream()
                        .allMatch(point -> point.value() <= 1_480);
                put(out, observationDate, "KRW_FX_REVERSAL_TRIGGER", "krw_fx_reversal_trigger",
                        fxStable && buyStreak >= 5 ? 1d : 0d,
                        "USDKRW <=1480 for five observations and foreign buy streak >=5");
            }
        }

        if (foreign.size() >= 6) {
            var recent = averageLastValues(foreign, 5, 0);
            var priorCount = Math.min(15, foreign.size() - 5);
            var prior = averageLastValues(foreign, priorCount, 5);
            put(out, observationDate, "KOSPI_FOREIGN_TREND", "kospi_foreign_trend", recent - prior,
                    "Foreign-flow latest 5D average minus preceding up-to-15D average (KRW 100M)");
        }

        if (foreign.size() >= 20) {
            var net20 = sumLast(foreign, 20);
            put(out, observationDate, "KOSPI_FOREIGN_NET_20D", "kospi_foreign_net_20d", net20,
                    "Latest twenty trading-day foreign net-purchase sum (KRW 100M)");
            put(out, observationDate, "KOSPI_FOREIGN_EXTREME", "kospi_foreign_extreme",
                    net20 >= 30_000 ? 1d : net20 <= -30_000 ? -1d : 0d,
                    "20D foreign flow >=+3T KRW / <=-3T KRW / neutral");
            put(out, observationDate, "KOSPI_FOREIGN_HISTORIC_EXTREME", "kospi_foreign_historic_extreme",
                    net20 >= 200_000 ? 1d : net20 <= -200_000 ? -1d : 0d,
                    "20D foreign flow >=+20T KRW / <=-20T KRW historic extreme / neutral");

            var individual = history(histories, "KRX:KOSPI_INDIVIDUAL_NET_1D");
            if (individual.size() >= 20) {
                put(out, observationDate, "KOSPI_INDIVIDUAL_NET_20D", "kospi_individual_net_20d",
                        sumLast(individual, 20), "Latest twenty trading-day individual net-purchase sum (KRW 100M)");
            }
        }

        if (foreign.size() >= 60) {
            var foreign60 = sumLast(foreign, 60);
            put(out, observationDate, "KOSPI_FOREIGN_OVERSELL_30T_FLAG", "kospi_foreign_oversell_30t_flag",
                    foreign60 <= -300_000 ? 1d : 0d,
                    "Latest 60 trading-day foreign flow <=-30T KRW");

            var fxHistory = history(histories, "YAHOO:USDKRW");
            if (fxHistory.size() >= 60) {
                var fxStart = fxHistory.get(fxHistory.size() - 60).value();
                var fxEnd = fxHistory.getLast().value();
                if (fxStart > 0) {
                    var fxChange = percent(fxEnd, fxStart);
                    var baselineTrillion = -fxChange * .5;
                    var actualTrillion = foreign60 / 10_000;
                    var gap = Math.abs(actualTrillion - baselineTrillion);
                    put(out, observationDate, "FX_FOREIGN_BASELINE_GAP_TRILLION",
                            "fx_foreign_baseline_gap_trillion", gap >= 30 ? 1d : 0d,
                            "Abs(actual foreign 60D - USDKRW-change baseline) >=30T KRW; baseline=-0.5T per FX 1%");
                }
            }
        }
    }

    private static int streak(List<MarketSeriesPoint> values, boolean positive) {
        var count = 0;
        for (var index = values.size() - 1; index >= 0; index--) {
            var value = values.get(index).value();
            if (positive ? value > 0 : value < 0) count++;
            else break;
        }
        return count;
    }

    private static double sumLast(List<MarketSeriesPoint> values, int count) {
        return values.subList(values.size() - count, values.size()).stream()
                .mapToDouble(MarketSeriesPoint::value)
                .sum();
    }

    private static void indexTechnicals(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String prefix,
            List<MarketSeriesPoint> points
    ) {
        if (points.isEmpty()) return;
        var closes = points.stream().map(MarketSeriesPoint::value).toList();
        var current = closes.getLast();
        for (var period : List.of(20, 50, 60, 100, 120, 200)) {
            var sma = averageLast(closes, period);
            if (sma == null || sma == 0) continue;
            put(out, asOf, prefix + "_SMA" + period,
                    prefix.toLowerCase() + "_sma" + period, sma,
                    "SMA(" + prefix + ", " + period + ")");
            if (period == 200) {
                put(out, asOf, prefix + "_DISPARITY", prefix.toLowerCase() + "_disparity_200",
                        percent(current, sma), "(PRICE - SMA200) / SMA200 * 100");
                put(out, asOf, prefix + "_ABOVE_200DMA", prefix.toLowerCase() + "_above_200dma",
                        current > sma ? 1d : 0d, "PRICE > SMA200 ? 1 : 0");
            } else {
                put(out, asOf, prefix + "_DISPARITY_" + period,
                        prefix.toLowerCase() + "_disparity_" + period,
                        percent(current, sma), "(PRICE - SMA" + period + ") / SMA" + period + " * 100");
            }
        }
        var high = closes.stream().mapToDouble(Double::doubleValue).max().orElse(current);
        put(out, asOf, prefix + "_DRAWDOWN", prefix.toLowerCase() + "_drawdown",
                percent(current, high), "(PRICE - observed high) / observed high * 100");
        put(out, asOf, prefix + "_DRAWDOWN_ATH", prefix.toLowerCase() + "_drawdown_ath",
                percent(current, high), "(PRICE - observed high) / observed high * 100");
        var rsi = rsi(closes, 14);
        put(out, asOf, prefix + "_RSI_14", prefix.toLowerCase() + "_rsi_14", rsi,
                "RSI(14) from Spring-owned daily close history");
        marketPriceStructure(out, asOf, prefix, points);
    }

    private static void assetTechnicals(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String prefix,
            List<MarketSeriesPoint> points
    ) {
        if (points.isEmpty()) return;
        var closes = points.stream().map(MarketSeriesPoint::value).toList();
        var current = closes.getLast();
        var sma200 = averageLast(closes, 200);
        if (sma200 != null && sma200 > 0) {
            put(out, asOf, prefix + "_SMA200", prefix.toLowerCase() + "_sma200", sma200, "SMA(200)");
            put(out, asOf, prefix + "_DISPARITY", prefix.toLowerCase() + "_disparity_200",
                    percent(current, sma200), "(PRICE - SMA200) / SMA200 * 100");
            put(out, asOf, prefix + "_ABOVE_200DMA", prefix.toLowerCase() + "_above_200dma",
                    current > sma200 ? 1d : 0d, "PRICE > SMA200 ? 1 : 0");
        }
        put(out, asOf, prefix + "_RSI_14", prefix.toLowerCase() + "_rsi_14", rsi(closes, 14), "RSI(14)");
        marketPriceStructure(out, asOf, prefix, points);
    }

    /**
     * Fresh close-only market structure. Company structure additionally uses
     * OHLCV; this index/asset projection intentionally exposes its missing flow
     * axis instead of pretending RSI alone is a buy signal.
     */
    private static void marketPriceStructure(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String prefix,
            List<MarketSeriesPoint> points
    ) {
        if (points.size() < 60) return;
        var closes = points.stream().map(MarketSeriesPoint::value).toList();
        var current = closes.getLast();
        var sma20 = averageLast(closes, 20);
        var sma50 = averageLast(closes, 50);
        var sma100 = averageLast(closes, 100);
        var sma200 = averageLast(closes, 200);
        var convergence = convergencePct(sma20, sma50, sma100, sma200);
        put(out, asOf, prefix + "_DMA_CONVERGENCE_PCT",
                prefix.toLowerCase() + "_dma_convergence_pct", convergence,
                "(max(SMA20,50,100,200)-min) / mean * 100; available averages only");
        put(out, asOf, prefix + "_DMA_CONVERGENCE_LEVEL",
                prefix.toLowerCase() + "_dma_convergence_level",
                convergence == null ? null : convergence <= 4 ? 1d : 0d,
                "1 when major moving-average spread <=4%; direction still requires confirmation");

        var channel = closeChannel(closes, Math.min(252, closes.size()));
        if (channel != null) {
            put(out, asOf, prefix + "_STRUCTURE_CHANNEL_LOWER",
                    prefix.toLowerCase() + "_structure_channel_lower", channel.lower(),
                    "252-session log OLS expected close - 2 residual standard deviations");
            put(out, asOf, prefix + "_STRUCTURE_CHANNEL_MID",
                    prefix.toLowerCase() + "_structure_channel_mid", channel.mid(),
                    "252-session log OLS expected close");
            put(out, asOf, prefix + "_STRUCTURE_CHANNEL_UPPER",
                    prefix.toLowerCase() + "_structure_channel_upper", channel.upper(),
                    "252-session log OLS expected close + 2 residual standard deviations");
            put(out, asOf, prefix + "_STRUCTURE_CHANNEL_POSITION",
                    prefix.toLowerCase() + "_structure_channel_position", channel.positionPct(),
                    "(close-lower)/(upper-lower)*100; not a standalone buy/sell signal");
            put(out, asOf, prefix + "_STRUCTURE_CHANNEL_SLOPE",
                    prefix.toLowerCase() + "_structure_channel_slope", channel.annualizedSlopePct(),
                    "exp(log OLS daily slope*252)-1 (%)");
        }

        var swings = closeSwings(closes, 5);
        var highs = swings.stream().filter(value -> value.high()).toList();
        var lows = swings.stream().filter(value -> !value.high()).toList();
        var trend = 0d;
        if (highs.size() >= 2 && lows.size() >= 2) {
            var risingHigh = highs.getLast().value() > highs.get(highs.size() - 2).value() * 1.005;
            var fallingHigh = highs.getLast().value() < highs.get(highs.size() - 2).value() * .995;
            var risingLow = lows.getLast().value() > lows.get(lows.size() - 2).value() * 1.005;
            var fallingLow = lows.getLast().value() < lows.get(lows.size() - 2).value() * .995;
            if (risingHigh && risingLow) trend = 1d;
            else if (fallingHigh && fallingLow) trend = -1d;
        }
        put(out, asOf, prefix + "_DOW_TREND_STATE",
                prefix.toLowerCase() + "_dow_trend_state", trend,
                "+1=higher high+higher low, -1=lower high+lower low, 0=range/transition");

        var stage = 0d;
        if (!lows.isEmpty() && current < lows.getLast().value() * .99) stage = 3d;
        else if (highs.size() >= 2
                && highs.getLast().value() < highs.get(highs.size() - 2).value() * .99) stage = 2d;
        else {
            var rsi = rsi(closes, 14);
            var recentHigh = closes.subList(Math.max(0, closes.size() - 60), closes.size())
                    .stream().mapToDouble(Double::doubleValue).max().orElse(current);
            if (rsi != null && rsi < 48 && current < recentHigh * .98) stage = 1d;
        }
        put(out, asOf, prefix + "_BEARISH_REVERSAL_STAGE",
                prefix.toLowerCase() + "_bearish_reversal_stage", stage,
                "0=intact, 1=momentum weakening, 2=lower-high structural crack, 3=prior swing-low break");

        var zones = closeZones(swings, Math.max(0, closes.size() - 504));
        var support = zones.stream()
                .filter(value -> value.center() <= current * 1.025)
                .min(Comparator.comparingDouble(value -> Math.abs(current - value.center())))
                .orElse(null);
        var resistance = zones.stream()
                .filter(value -> value.center() >= current * .975)
                .min(Comparator.comparingDouble(value -> Math.abs(current - value.center())))
                .orElse(null);
        if (support != null) {
            put(out, asOf, prefix + "_SUPPORT_ZONE_LOW",
                    prefix.toLowerCase() + "_support_zone_low", support.center() * .99,
                    "Nearest repeated close-pivot support cluster center -1%");
            put(out, asOf, prefix + "_SUPPORT_ZONE_HIGH",
                    prefix.toLowerCase() + "_support_zone_high", support.center() * 1.01,
                    "Nearest repeated close-pivot support cluster center +1%");
        }
        if (resistance != null) {
            put(out, asOf, prefix + "_RESISTANCE_ZONE_LOW",
                    prefix.toLowerCase() + "_resistance_zone_low", resistance.center() * .99,
                    "Nearest repeated close-pivot resistance cluster center -1%");
            put(out, asOf, prefix + "_RESISTANCE_ZONE_HIGH",
                    prefix.toLowerCase() + "_resistance_zone_high", resistance.center() * 1.01,
                    "Nearest repeated close-pivot resistance cluster center +1%");
        }
        marketFibonacci(out, asOf, prefix, points, swings, support, resistance, channel);

        var rangeDays = closeRangeDuration(closes);
        put(out, asOf, prefix + "_RANGE_DURATION",
                prefix.toLowerCase() + "_range_duration", (double) rangeDays,
                "Longest trailing 20..120-session bounded close range; time-correction proxy");

        var nearSupport = support != null
                && current >= support.center() * .975
                && current <= support.center() * 1.025;
        var lowerChannel = channel != null && channel.positionPct() <= 22;
        var upperChannel = channel != null && channel.positionPct() >= 82;
        var breakdown = (support != null && current < support.center() * .975)
                || (channel != null && current < channel.lower() * .985);
        var location = breakdown ? -2d : nearSupport || lowerChannel ? -1d : upperChannel ? 1d : 0d;
        put(out, asOf, prefix + "_PRICE_LOCATION_STATE",
                prefix.toLowerCase() + "_price_location_state", location,
                "-2=breakdown, -1=support/lower channel, 0=middle, +1=upper channel");

        var currentRsi = rsi(closes, 14);
        var rsiConfluence = currentRsi != null && currentRsi <= 35
                && (nearSupport || lowerChannel) && stage < 3;
        put(out, asOf, prefix + "_RSI_SUPPORT_CONFLUENCE",
                prefix.toLowerCase() + "_rsi_support_confluence", rsiConfluence ? 1d : 0d,
                "RSI<=35 plus support/lower channel and no stage-3 break; flow confirmation is still required");

        var structureScore = 50d + trend * 12 - stage * (stage == 3 ? 12 : stage == 2 ? 9 : 6);
        if (nearSupport || lowerChannel) structureScore += 10;
        if (upperChannel) structureScore -= 8;
        if (breakdown) structureScore -= 20;
        if (sma20 != null && sma50 != null && sma100 != null && sma200 != null
                && current > sma20 && sma20 > sma50 && sma50 > sma100 && sma100 > sma200) {
            structureScore += 10;
        }
        if (rsiConfluence) structureScore += 6;
        put(out, asOf, prefix + "_STRUCTURE_SCORE",
                prefix.toLowerCase() + "_structure_score", clamp(structureScore, 0, 100),
                "Close-only structure confluence score; not a probability and not a standalone action");
    }

    private static void marketFibonacci(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String prefix,
            List<MarketSeriesPoint> points,
            List<CloseSwing> rawSwings,
            CloseZone support,
            CloseZone resistance,
            CloseChannel channel
    ) {
        var swing = selectMarketFibSwing(rawSwings, Math.max(0, points.size() - 504));
        if (swing == null) return;
        var up = !swing.start().high() && swing.end().high();
        var low = Math.min(swing.start().value(), swing.end().value());
        var high = Math.max(swing.start().value(), swing.end().value());
        var range = high - low;
        if (range <= 0) return;
        var current = points.getLast().value();
        var ratios = List.of(.236, .382, .5, .618, .786);
        var levels = new LinkedHashMap<Double, Double>();
        for (var ratio : ratios) levels.put(ratio, up ? high - range * ratio : low + range * ratio);
        var nearest = levels.entrySet().stream()
                .min(Comparator.comparingDouble(value -> Math.abs(value.getValue() - current)))
                .orElseThrow();
        var currentRatio = up ? (high - current) / range : (current - low) / range;

        var weeklyPoints = weeklyMarketPoints(points);
        var weeklySwing = weeklyPoints.size() < 12 ? null
                : selectMarketFibSwing(
                        closeSwings(weeklyPoints.stream().map(MarketSeriesPoint::value).toList(), 2),
                        Math.max(0, weeklyPoints.size() - 104)
                );
        var weeklyConfluence = false;
        if (weeklySwing != null) {
            var weeklyUp = !weeklySwing.start().high() && weeklySwing.end().high();
            var weeklyLow = Math.min(weeklySwing.start().value(), weeklySwing.end().value());
            var weeklyHigh = Math.max(weeklySwing.start().value(), weeklySwing.end().value());
            var weeklyRange = weeklyHigh - weeklyLow;
            weeklyConfluence = weeklyRange > 0 && ratios.stream()
                    .map(ratio -> weeklyUp
                            ? weeklyHigh - weeklyRange * ratio
                            : weeklyLow + weeklyRange * ratio)
                    .anyMatch(value -> Math.abs(value / nearest.getValue() - 1) <= .025);
        }
        var relevantZone = up ? support : resistance;
        var zoneConfluence = relevantZone != null
                && Math.abs(nearest.getValue() / relevantZone.center() - 1) <= .025;
        var channelConfluence = channel != null && java.util.stream.Stream.of(
                        channel.lower(), channel.mid(), channel.upper())
                .anyMatch(value -> Math.abs(value / nearest.getValue() - 1) <= .02);
        var confluenceScore = Math.min(
                100,
                (weeklyConfluence ? 35 : 0) + (zoneConfluence ? 40 : 0) + (channelConfluence ? 25 : 0)
        );
        var startDate = points.get(swing.start().index()).date();
        var endDate = points.get(swing.end().index()).date();
        var baseFormula = "latest clear major close swing %s→%s (%s); "
                .formatted(startDate, endDate, up ? "low-to-high" : "high-to-low");
        levels.forEach((ratio, price) -> put(
                out,
                asOf,
                prefix + "_FIB_" + fibonacciKey(ratio),
                prefix.toLowerCase() + "_fib_" + fibonacciKey(ratio).toLowerCase(),
                price,
                baseFormula + (up ? "HIGH-(HIGH-LOW)*" : "LOW+(HIGH-LOW)*") + ratio
        ));
        put(out, asOf, prefix + "_FIB_SWING_DIRECTION", prefix.toLowerCase() + "_fib_swing_direction",
                up ? 1d : -1d, "+1=latest major low-to-high swing, -1=high-to-low swing");
        put(out, asOf, prefix + "_FIB_CURRENT_RETRACEMENT", prefix.toLowerCase() + "_fib_current_retracement",
                currentRatio * 100, "Current retracement ratio of the selected major swing (%)");
        put(out, asOf, prefix + "_FIB_NEAREST_RATIO", prefix.toLowerCase() + "_fib_nearest_ratio",
                nearest.getKey(), "Nearest standard ratio among 0.236/0.382/0.5/0.618/0.786");
        put(out, asOf, prefix + "_FIB_WEEKLY_CONFLUENCE", prefix.toLowerCase() + "_fib_weekly_confluence",
                weeklyConfluence ? 1d : 0d, "1 when an independently selected weekly swing level is within 2.5%");
        put(out, asOf, prefix + "_FIB_SUPPORT_CONFLUENCE", prefix.toLowerCase() + "_fib_support_confluence",
                zoneConfluence ? 1d : 0d,
                "1 when the nearest Fibonacci level overlaps an independently clustered support/resistance zone");
        put(out, asOf, prefix + "_FIB_CONFLUENCE_SCORE", prefix.toLowerCase() + "_fib_confluence_score",
                (double) confluenceScore,
                "35 weekly aggregation +40 independent support/resistance +25 regression-channel overlap; "
                        + "zero without corroboration and not a probability");
        put(out, asOf, prefix + "_FIB_LAST_DEFENSE_BROKEN",
                prefix.toLowerCase() + "_fib_last_defense_broken",
                currentRatio > .806 ? 1d : 0d,
                "1 when price has moved beyond the selected swing's 0.786 reference with a 2% ratio buffer; "
                        + "not proof of a trend break by itself");
    }

    private static MarketFibSwing selectMarketFibSwing(List<CloseSwing> raw, int firstIndex) {
        var alternating = new ArrayList<CloseSwing>();
        for (var value : raw) {
            if (value.index() < firstIndex) continue;
            if (alternating.isEmpty()) {
                alternating.add(value);
                continue;
            }
            var prior = alternating.getLast();
            if (prior.index() == value.index()) continue;
            if (prior.high() != value.high()) {
                alternating.add(value);
                continue;
            }
            var moreExtreme = value.high() ? value.value() > prior.value() : value.value() < prior.value();
            if (moreExtreme) alternating.set(alternating.size() - 1, value);
        }
        MarketFibSwing fallback = null;
        MarketFibSwing latestMajor = null;
        for (var index = 1; index < alternating.size(); index++) {
            var start = alternating.get(index - 1);
            var end = alternating.get(index);
            if (start.high() == end.high() || end.index() - start.index() < 4) continue;
            var amplitude = Math.abs(end.value() / start.value() - 1) * 100;
            var candidate = new MarketFibSwing(start, end, amplitude);
            if (fallback == null || amplitude > fallback.amplitudePct()) fallback = candidate;
            if (amplitude >= 8) latestMajor = candidate;
        }
        return latestMajor != null ? latestMajor
                : fallback != null && fallback.amplitudePct() >= 5 ? fallback : null;
    }

    private static List<MarketSeriesPoint> weeklyMarketPoints(List<MarketSeriesPoint> points) {
        var fields = java.time.temporal.WeekFields.ISO;
        var values = new LinkedHashMap<String, MarketSeriesPoint>();
        for (var point : points) {
            var key = point.date().get(fields.weekBasedYear()) + "-"
                    + point.date().get(fields.weekOfWeekBasedYear());
            values.put(key, point);
        }
        return List.copyOf(values.values());
    }

    private static String fibonacciKey(double ratio) {
        if (ratio == .236) return "236";
        if (ratio == .382) return "382";
        if (ratio == .5) return "500";
        if (ratio == .618) return "618";
        return "786";
    }

    private static void assetMomentum(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String prefix,
            List<MarketSeriesPoint> points,
            int lookback
    ) {
        put(out, asOf, prefix + "_MOMENTUM", prefix.toLowerCase() + "_momentum",
                returnPct(points, lookback), lookback + " trading-point return (%)");
    }

    private static void oilInflationPressure(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var change = returnPct(history(histories, "YAHOO:WTI"), 60);
        if (change == null) return;
        put(out, asOf, "WTI_60D_CHANGE", "wti_60d_change", change,
                "WTI latest sixty-trading-point return (%)");
        var pressure = change <= -15 ? -2d : change <= -5 ? -1d : change < 5 ? 0d : change < 15 ? 1d : 2d;
        put(out, asOf, "CPI_OIL_LAG_PRESSURE", "cpi_oil_lag_pressure", pressure,
                "WTI 60D change mapped to expected two-to-three-month CPI pressure (-2..2)");
    }

    private static void sectorMomentum(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var priceBenchmark = history(histories, "YAHOO:SP500");
        var totalReturnBenchmark = history(histories, "YAHOO:SPY_TR");
        var standardSectorMomentum = new LinkedHashMap<String, Double>();
        var strategicThemeMomentum = new LinkedHashMap<String, Double>();
        String strongest = null;
        double strongestValue = -Double.MAX_VALUE;
        int totalReturnReady = 0;
        for (var key : SECTOR_KEYS) {
            var pricePoints = history(histories, "YAHOO:" + key);
            var totalReturnPoints = history(histories, "YAHOO:" + key + "_TR");
            var hasTotalReturn = totalReturnPoints.size() > 273 && totalReturnBenchmark.size() > 273;
            var comparisonPoints = hasTotalReturn ? totalReturnPoints : pricePoints;
            var comparisonBenchmark = hasTotalReturn ? totalReturnBenchmark : priceBenchmark;
            if (hasTotalReturn) totalReturnReady++;
            var one = returnPct(pricePoints, 21);
            var three = returnPct(pricePoints, 63);
            var six = returnPct(pricePoints, 126);
            var twelve = returnPct(pricePoints, 252);
            put(out, asOf, "SECTOR_" + key, "sector_" + key.toLowerCase(), one,
                    key + " ETF recent one-month return (%)");
            put(out, asOf, "SECTOR_RET_3M_" + key, "sector_ret_3m_" + key.toLowerCase(), three,
                    key + " ETF recent three-month return (%)");
            put(out, asOf, "SECTOR_RET_6M_" + key, "sector_ret_6m_" + key.toLowerCase(), six,
                    key + " ETF recent six-month return (%)");
            put(out, asOf, "SECTOR_RET_12M_" + key, "sector_ret_12m_" + key.toLowerCase(), twelve,
                    key + " ETF recent twelve-month return (%)");
            put(out, asOf, "SECTOR_TR_RET_1M_" + key, "sector_tr_ret_1m_" + key.toLowerCase(),
                    returnPct(totalReturnPoints, 21), key + " adjusted-close total return, one month (%)");
            put(out, asOf, "SECTOR_TR_RET_3M_" + key, "sector_tr_ret_3m_" + key.toLowerCase(),
                    returnPct(totalReturnPoints, 63), key + " adjusted-close total return, three months (%)");
            put(out, asOf, "SECTOR_TR_RET_6M_" + key, "sector_tr_ret_6m_" + key.toLowerCase(),
                    returnPct(totalReturnPoints, 126), key + " adjusted-close total return, six months (%)");
            put(out, asOf, "SECTOR_TR_RET_12M_" + key, "sector_tr_ret_12m_" + key.toLowerCase(),
                    returnPct(totalReturnPoints, 252), key + " adjusted-close total return, twelve months (%)");
            put(out, asOf, "SECTOR_TR_READY_" + key, "sector_tr_ready_" + key.toLowerCase(),
                    hasTotalReturn ? 1d : 0d,
                    hasTotalReturn ? "Adjusted-close sector and SPY histories are ready"
                            : "Adjusted-close history unavailable; price-relative fallback is active");
            var relativeOne = relativeReturnPct(comparisonPoints, comparisonBenchmark, 21);
            var relativeThree = relativeReturnPct(comparisonPoints, comparisonBenchmark, 63);
            var relativeSix = relativeReturnPct(comparisonPoints, comparisonBenchmark, 126);
            var relativeTwelve = relativeReturnPct(comparisonPoints, comparisonBenchmark, 252);
            var basis = hasTotalReturn ? "distribution-adjusted total-return ratio versus SPY"
                    : "price ratio versus S&P 500 (explicit fallback)";
            put(out, asOf, "SECTOR_REL_1M_" + key, "sector_rel_1m_" + key.toLowerCase(), relativeOne,
                    key + " " + basis + ", recent one-month return (%)");
            put(out, asOf, "SECTOR_REL_3M_" + key, "sector_rel_3m_" + key.toLowerCase(), relativeThree,
                    key + " " + basis + ", recent three-month return (%)");
            put(out, asOf, "SECTOR_REL_6M_" + key, "sector_rel_6m_" + key.toLowerCase(), relativeSix,
                    key + " " + basis + ", recent six-month return (%)");
            put(out, asOf, "SECTOR_REL_12M_" + key, "sector_rel_12m_" + key.toLowerCase(), relativeTwelve,
                    key + " " + basis + ", recent twelve-month return (%)");
            var legacyWeightedRelative = weighted(
                    relativeOne, .15, relativeThree, .35,
                    relativeSix, .35, relativeTwelve, .15);
            put(out, asOf, "SECTOR_RS_LEGACY_" + key, "sector_rs_legacy_" + key.toLowerCase(),
                    legacyWeightedRelative,
                    "Comparison-only V1 benchmark-relative strength: "
                            + "1M*15% + 3M*35% + 6M*35% + 12M*15%");
            var relativeSixExRecentMonth = relativeReturnPctBetween(
                    comparisonPoints, comparisonBenchmark, 147, 21);
            var relativeTwelveExRecentMonth = relativeReturnPctBetween(
                    comparisonPoints, comparisonBenchmark, 273, 21);
            var mediumTermRelative = relativeSixExRecentMonth == null || relativeTwelveExRecentMonth == null
                    ? null
                    : (relativeSixExRecentMonth + relativeTwelveExRecentMonth) / 2d;
            var relativeVolatility = relativeVolatility(
                    comparisonPoints, comparisonBenchmark, 252, 21);
            var riskAdjusted = mediumTermRelative == null || relativeVolatility == null
                    ? null
                    : mediumTermRelative / Math.max(relativeVolatility, .0001);
            put(out, asOf, "SECTOR_RS_" + key, "sector_rs_" + key.toLowerCase(), mediumTermRelative,
                    "Current V2 benchmark-relative " + (hasTotalReturn ? "total-return" : "price-fallback")
                            + " momentum: equal-weight 6M and 12M formation returns, both ending one month ago, versus "
                            + (hasTotalReturn ? "SPY total return" : "S&P 500 price index"));
            put(out, asOf, "SECTOR_MOMENTUM_RISK_ADJ_" + key,
                    "sector_momentum_risk_adj_" + key.toLowerCase(), riskAdjusted,
                    "Current V2 relative momentum divided by annualized trailing-252-point relative volatility");
            if (riskAdjusted != null && Double.isFinite(riskAdjusted)) {
                var universe = STANDARD_SECTOR_KEYS.contains(key)
                        ? standardSectorMomentum
                        : strategicThemeMomentum;
                universe.put(key, riskAdjusted);
            }
            var absoluteTwelveExRecentMonth = returnPctBetween(comparisonPoints, 273, 21);
            var average200 = comparisonPoints.size() < 200
                    ? null
                    : comparisonPoints.subList(comparisonPoints.size() - 200, comparisonPoints.size())
                    .stream().mapToDouble(MarketSeriesPoint::value).average().orElse(0);
            var positiveAbsoluteTrend = absoluteTwelveExRecentMonth != null
                    && absoluteTwelveExRecentMonth > 0
                    && average200 != null && comparisonPoints.getLast().value() > average200;
            put(out, asOf, "SECTOR_ABSOLUTE_TREND_" + key,
                    "sector_absolute_trend_" + key.toLowerCase(), positiveAbsoluteTrend ? 1d : 0d,
                    "1 when 12M total return excluding the latest month is positive and latest value is above its 200-point mean");
            if (one != null && one > strongestValue) {
                strongest = key;
                strongestValue = one;
            }
        }
        publishMomentumPercentiles(
                out, asOf, standardSectorMomentum,
                "standard eleven-sector universe");
        publishMomentumPercentiles(
                out, asOf, strategicThemeMomentum,
                "separate five-ETF strategic-theme universe");
        if (strongest != null) {
            put(out, asOf, "SECTOR_STRONGEST", "sector_strongest", strongestValue,
                    "Strongest one-month sector: " + strongest);
        }
        put(out, asOf, "SECTOR_TOTAL_RETURN_COVERAGE", "sector_total_return_coverage",
                totalReturnReady * 100d / SECTOR_KEYS.size(),
                "Share of the 16-sector rotation universe using adjusted-close total-return history (%)");
    }

    private static void publishMomentumPercentiles(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            Map<String, Double> values,
            String universeLabel
    ) {
        values.forEach((key, ignored) -> put(
                out, asOf, "SECTOR_MOMENTUM_SCORE_" + key,
                "sector_momentum_score_" + key.toLowerCase(),
                crossSectionalPercentile(values, key),
                "Cross-sectional percentile of V2 risk-adjusted relative total-return momentum within the "
                        + universeLabel + " (0=lowest, 100=highest)"));
    }

    private static void credit(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            Map<String, Double> raw,
            LocalDate asOf
    ) {
        var hy = raw.get("BAMLH0A0HYM2");
        var hyBp = hy == null ? null : hy > 50 ? hy : hy * 100;
        put(out, asOf, "CREDIT_HY_OAS_BP", "credit_hy_oas_bp", hyBp,
                "BAMLH0A0HYM2 converted to basis points");
        var hyg = history(histories, "YAHOO:HYG");
        var ief = history(histories, "YAHOO:IEF");
        var iefByDate = new LinkedHashMap<LocalDate, Double>();
        ief.forEach(point -> iefByDate.put(point.date(), point.value()));
        var ratios = new ArrayList<Double>();
        for (var point : hyg) {
            var denominator = iefByDate.get(point.date());
            if (denominator != null && denominator > 0 && point.value() > 0) ratios.add(point.value() / denominator);
        }
        Double z = null;
        if (!ratios.isEmpty()) {
            put(out, asOf, "CREDIT_HYG_IEF_RATIO", "credit_hyg_ief_ratio", ratios.getLast(), "HYG / IEF");
        }
        if (ratios.size() >= 252) {
            var window = ratios.subList(ratios.size() - 252, ratios.size());
            var mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            var variance = window.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
            var stdev = Math.sqrt(variance);
            if (stdev > 0) z = (window.getLast() - mean) / stdev;
            put(out, asOf, "CREDIT_HYG_IEF_ZSCORE", "credit_hyg_ief_zscore", z,
                    "(HYG/IEF - 252-point mean) / 252-point standard deviation");
        }
        if (hyBp != null || z != null) {
            put(out, asOf, "CREDIT_STRESS_FLAG", "credit_stress_flag",
                    (hyBp != null && hyBp >= 600) || (z != null && z <= -2) ? 1d : 0d,
                    "HY OAS >= 600bp OR HYG/IEF z <= -2");
        }
    }

    private static void copperGoldTrend(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var copper = history(histories, "YAHOO:COPPER");
        var gold = history(histories, "YAHOO:GOLD");
        var goldByDate = new LinkedHashMap<LocalDate, Double>();
        gold.forEach(point -> goldByDate.put(point.date(), point.value()));
        var ratio = new ArrayList<Double>();
        for (var point : copper) {
            var g = goldByDate.get(point.date());
            if (g != null && g > 0 && point.value() > 0) ratio.add(point.value() / g);
        }
        if (ratio.size() < 25) return;
        var recent = average(ratio.subList(ratio.size() - 5, ratio.size()));
        var prior = average(ratio.subList(ratio.size() - 20, ratio.size() - 15));
        var middle = average(ratio.subList(ratio.size() - 15, ratio.size() - 10));
        var trend = prior == 0 ? 0 : (recent - prior) / prior;
        var priorTrend = middle == 0 ? 0 : (prior - middle) / middle;
        put(out, asOf, "COPPER_GOLD_RATIO_TREND", "copper_gold_ratio_trend", trend,
                "Recent 5-point copper/gold average vs 15~20-point-prior average");
        put(out, asOf, "COPPER_GOLD_RATIO_UPTURN", "copper_gold_ratio_upturn",
                trend > .005 && priorTrend <= .005 ? 1d : 0d,
                "Copper/gold trend > +0.5% after a neutral/down prior interval");
        put(out, asOf, "COPPER_GOLD_RATIO_DOWNTURN", "copper_gold_ratio_downturn",
                trend < -.005 && priorTrend >= -.005 ? 1d : 0d,
                "Copper/gold trend < -0.5% after a neutral/up prior interval");
    }

    private static void tiers(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            LocalDate asOf
    ) {
        var dxy = raw.get("DXY");
        if (dxy != null) {
            var tier = dxy >= 105 ? -1d : dxy >= 100 ? 0d : dxy >= 95 ? 1d : 2d;
            put(out, asOf, "DXY_TIER", "dxy_tier", tier, "DXY 105/100/95 tier");
        }
        var usdkrw = raw.get("USDKRW");
        if (usdkrw != null) {
            var level = usdkrw <= 1400 ? 2d : usdkrw <= 1480 ? 1d : usdkrw <= 1500 ? 0d : usdkrw <= 1550 ? -1d : -2d;
            put(out, asOf, "KRW_FX_LEVEL", "krw_fx_level", level, "USDKRW <=1400/+2, <=1480/+1, <=1500/0, <=1550/-1, else -2");
            put(out, asOf, "KRW_FX_GREEN", "krw_fx_green", usdkrw <= 1480 ? 1d : 0d, "USDKRW <= 1480");
            put(out, asOf, "KRW_FX_RED", "krw_fx_red", usdkrw >= 1500 ? 1d : 0d, "USDKRW >= 1500");
        }
    }

    private static void goldilocks(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            Double cpiYoy,
            Double pceYoy,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        Double ism = null;
        var indpro = history(histories, "FRED:INDPRO");
        if (indpro.size() >= 3) {
            var current = indpro.getLast().value();
            var previous = indpro.get(indpro.size() - 2).value();
            var before = indpro.get(indpro.size() - 3).value();
            var mom = previous == 0 ? 0 : percent(current, previous);
            var proxy = clamp(50 + mom * 10, 30, 70);
            if (current > previous && previous > before) proxy = Math.max(proxy, 51);
            if (current < previous && previous < before) proxy = Math.min(proxy, 49);
            ism = proxy;
            put(out, asOf, "ISM_PROXY", "ism_proxy", ism, "Industrial-production momentum ISM proxy");
        }
        var total = pricingScore(cpiYoy) + pricingScore(pceYoy) + ismScore(ism) + unemploymentScore(raw.get("UNRATE"));
        var zone = total >= 4 ? 2d : total >= 1 ? 1d : total >= -2 ? 0d : -1d;
        put(out, asOf, "GOLDILOCKS_ZONE", "goldilocks_zone", zone,
                "CPI/PCE/ISM/unemployment four-axis macro temperature");
    }

    private static void flags(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var disparity = value(out, "NASDAQ_DISPARITY");
        var fng = raw.get("FEAR_GREED");
        var vix = raw.get("VIXCLS");
        Double overheated = null;
        if (disparity != null && disparity > 20 && fng != null && fng > 75) overheated = 1d;
        else if (disparity != null && disparity > 15 && vix != null && vix < 15) overheated = 1d;
        else if (vix != null || fng != null) overheated = 0d;
        put(out, asOf, "OVERHEATED", "overheated", overheated,
                "NASDAQ disparity plus Fear & Greed/VIX confirmation");

        var cpi = value(out, "CPI_YOY");
        var pce = value(out, "PCE_YOY");
        var ism = value(out, "ISM_PROXY");
        var oilPressure = value(out, "CPI_OIL_LAG_PRESSURE");
        var inflationHot = (oilPressure != null && oilPressure >= 1)
                || (cpi != null && cpi > 3.5) || (pce != null && pce > 3.2);
        var growthSlow = (ism != null && ism < 48) || (raw.get("UNRATE") != null && raw.get("UNRATE") >= 5);
        put(out, asOf, "STAGFLATION_SCORE", "stagflation_score", (inflationHot ? 1d : 0d) + (growthSlow ? 1d : 0d),
                "Inflation pressure plus growth slowdown");
        put(out, asOf, "STAGFLATION_WARNING", "stagflation_warning", inflationHot && growthSlow ? 1d : 0d,
                "Inflation pressure and growth slowdown both active");

        fiscalAndBondVigilante(out, raw, histories, asOf);

        var drawdown = value(out, "NASDAQ_DRAWDOWN_ATH");
        var icsa = raw.get("ICSA");
        if (drawdown != null && drawdown <= -30) {
            var classification = icsa != null && icsa < 300_000 && ism != null && ism >= 48
                    ? -1d
                    : (icsa != null && icsa >= 350_000) || (ism != null && ism < 45) ? -2d : 0d;
            put(out, asOf, "DRAWDOWN_TYPE_CLASSIFIER", "drawdown_type_classifier", classification,
                    "-1 opportunity, -2 systemic risk, 0 ambiguous at drawdown <= -30%");
        }
    }

    private static void fiscalAndBondVigilante(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var dgs30History = history(histories, "FRED:DGS30");
        var dgs30 = raw.get("DGS30");
        if (dgs30 == null && !dgs30History.isEmpty()) dgs30 = dgs30History.getLast().value();
        if (dgs30 == null) return;

        if (dgs30History.size() >= 20) {
            var previous = dgs30History.get(dgs30History.size() - 20).value();
            var change = dgs30 - previous;
            put(out, asOf, "DGS30_20D_CHANGE", "dgs30_20d_change", change,
                    "Current DGS30 minus the nineteenth prior daily observation (%p)");
            var fiscalStress = (change >= .2 && dgs30 >= 4.8) || change >= .3;
            var curve = raw.get("T10Y2Y");
            put(out, asOf, "FISCAL_STRESS", "fiscal_stress", fiscalStress ? 1d : 0d,
                    "DGS30 20D >=+0.2%p with level >=4.8%, or 20D >=+0.3%p");
            put(out, asOf, "FISCAL_STRESS_HARD", "fiscal_stress_hard",
                    fiscalStress && curve != null && curve > .1 ? 1d : 0d,
                    "FISCAL_STRESS=1 and T10Y2Y > 0.1%p");
        }

        var dgs10 = raw.get("DGS10");
        var dxy = raw.get("DXY");
        var dxyTrend = value(out, "DXY_TREND_LONG");
        var credit = value(out, "CREDIT_HY_OAS_BP");
        var creditZ = value(out, "CREDIT_HYG_IEF_ZSCORE");
        var steepeningAxis = dgs10 != null && dgs30 - dgs10 > .4;
        var longYieldAxis = dgs30 >= 4.8;
        var dollarAxis = (dxy != null && dxy < 100) || (dxyTrend != null && dxyTrend < -2);
        var creditAxis = (credit != null && credit >= 500) || (creditZ != null && creditZ <= -1.5);
        var score = (steepeningAxis ? 1 : 0) + (longYieldAxis ? 1 : 0)
                + (dollarAxis ? 1 : 0) + (creditAxis ? 1 : 0);
        put(out, asOf, "BOND_VIGILANTE_SCORE", "bond_vigilante_score", (double) score,
                "Four axes: DGS30-DGS10>0.4, DGS30>=4.8, weak DXY, stressed HY credit");
        put(out, asOf, "BOND_VIGILANTE_WARNING", "bond_vigilante_warning", score >= 3 ? 1d : 0d,
                "At least three of four bond-vigilante axes are active");
    }

    private static Double liquidityDirection(Map<String, CoreDerivedIndicator> values) {
        var netImpulse = value(values, "NET_LIQUIDITY_IMPULSE_STATE");
        if (netImpulse != null) return netImpulse;
        double score = 0;
        int count = 0;
        for (var entry : List.of(
                Map.entry("RRP_DIRECTION", -1d), Map.entry("TGA_DIRECTION", -1d),
                Map.entry("MMF_DIRECTION", -1d), Map.entry("WRESBAL_DIRECTION", 1d))) {
            var value = value(values, entry.getKey());
            if (value == null) continue;
            score += Math.signum(value) * entry.getValue();
            count++;
        }
        var m2 = value(values, "GLOBAL_M2_PROXY");
        if (m2 != null) {
            score += m2 > 1 ? 1 : m2 < -1 ? -1 : 0;
            count++;
        }
        if (count == 0) return null;
        var average = score / count;
        return average >= .6 ? 2d : average > 0 ? 1d : average <= -.6 ? -2d : average < 0 ? -1d : 0d;
    }

    private static void reserveBalanceCushion(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories
    ) {
        var points = history(histories, "FRED:WRESBAL");
        if (points.isEmpty()) return;
        var latest = points.getLast();
        var levelTn = latest.value() / 1_000_000d;
        put(out, latest.date(), "WRESBAL_LEVEL_TN", "wresbal_level_tn", levelTn,
                "Reserve balances with Federal Reserve Banks / 1,000,000; USD trillions");
        put(out, latest.date(), "WRESBAL_ABSOLUTE_LEVEL", "wresbal_absolute_level",
                latest.value() >= 3_000_000d ? 1d : 0d,
                "1 at or above the 3tn monitoring threshold, 0 below; heuristic liquidity cushion, not an official safety boundary or return signal");
    }

    private static void liquidityPlumbing(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf
    ) {
        var currentAxisKeys = List.of("TGA_DIRECTION", "RRP_DIRECTION", "WRESBAL_DIRECTION");
        var axes = java.util.Arrays.asList(
                liquidityAxis(value(out, "TGA_DIRECTION"), false, 1.0),
                liquidityAxis(value(out, "RRP_DIRECTION"), false, 1.0),
                liquidityAxis(value(out, "WRESBAL_DIRECTION"), true, 0.5)
        );
        var available = (int) axes.stream().filter(value -> value != null).count();
        if (available == 0) return;
        var bullish = (int) axes.stream().filter(value -> value != null && value > 0).count();
        var bearish = (int) axes.stream().filter(value -> value != null && value < 0).count();
        var neutral = available - bullish - bearish;
        var signed = bullish - bearish;
        var score = clamp(50 + signed * 50.0 / available, 0, 100);
        var signal = available == 3 && bullish == 3 ? 2d
                : bullish >= 2 && bearish == 0 ? 1d
                : available == 3 && bearish == 3 ? -2d
                : bearish >= 2 && bullish == 0 ? -1d : 0d;
        var dataDate = currentAxisKeys.stream().map(out::get).filter(java.util.Objects::nonNull)
                .map(CoreDerivedIndicator::date).min(LocalDate::compareTo).orElse(asOf);

        put(out, dataDate, "LIQUIDITY_PLUMBING_SCORE", "liquidity_plumbing_score", score,
                "current TGA↓ + RRP↓ + reserve balances↑ alignment; correlated balance-sheet confirmations, "
                        + "not independent factors; bullish minus bearish axes normalized to 0..100");
        put(out, dataDate, "LIQUIDITY_PLUMBING_SIGNAL", "liquidity_plumbing_signal", signal,
                "+2=all three current axes expansionary, +1=at least two expansionary without drain, "
                        + "0=mixed, -1=at least two draining, -2=all three draining; lagged issuance excluded");
        put(out, dataDate, "LIQUIDITY_PLUMBING_BULLISH_AXES", "liquidity_plumbing_bullish_axes",
                (double) bullish, "Expansionary current liquidity-plumbing axes out of available three");
        put(out, dataDate, "LIQUIDITY_PLUMBING_BEARISH_AXES", "liquidity_plumbing_bearish_axes",
                (double) bearish, "Liquidity-draining current plumbing axes out of available three");
        put(out, dataDate, "LIQUIDITY_PLUMBING_NEUTRAL_AXES", "liquidity_plumbing_neutral_axes",
                (double) neutral, "Neutral current plumbing axes out of available three");
        put(out, dataDate, "LIQUIDITY_PLUMBING_CONFIDENCE", "liquidity_plumbing_confidence",
                available * 100d / 3d, "Available current official inputs / three axes * 100; data coverage, not statistical confidence and not a return probability");
    }

    private static Double liquidityAxis(Double direction, boolean positiveIsFavorable, double neutralThreshold) {
        if (direction == null) return null;
        if (Math.abs(direction) < neutralThreshold) return 0d;
        var sign = Math.signum(direction);
        return positiveIsFavorable ? sign : -sign;
    }

    private static void m2Impulse(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var points = history(histories, "FRED:M2SL");
        if (points.size() < 13) return;
        var latest = points.getLast();
        var threeMonthsAgo = pointOnOrBefore(points, latest.date().minusMonths(3), 45);
        if (threeMonthsAgo == null || threeMonthsAgo.value() <= 0 || latest.value() <= 0) return;
        var annualized = (Math.pow(latest.value() / threeMonthsAgo.value(), 4) - 1) * 100;
        put(out, latest.date(), "US_M2_3M_ANNUALIZED", "us_m2_3m_annualized", annualized,
                "M2SL latest / three-month-prior annualized; monthly and lagged, not a market-timing signal");
        var yoy = value(out, "US_M2_YOY");
        if (yoy != null) {
            put(out, latest.date(), "US_M2_GROWTH_ACCELERATION", "us_m2_growth_acceleration",
                    annualized - yoy,
                    "M2 three-month annualized growth minus year-over-year growth (%p); direction/speed context only");
        }
    }

    /**
     * Weekly point-in-time net-liquidity proxy. FRED WALCL and WDTGAL are USD
     * millions while RRPONTSYD is USD billions, so RRP is converted before the
     * subtraction. Every leg is joined as-of a common anchor date to prevent a
     * later daily observation leaking into an earlier weekly balance-sheet point.
     */
    private static void netLiquidityImpulse(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories
    ) {
        var walcl = history(histories, "FRED:WALCL");
        var tga = history(histories, "FRED:WDTGAL");
        var rrp = history(histories, "FRED:RRPONTSYD");
        if (walcl.isEmpty() || tga.isEmpty() || rrp.isEmpty()) return;
        var anchor = java.util.stream.Stream.of(
                        walcl.getLast().date(), tga.getLast().date(), rrp.getLast().date())
                .min(LocalDate::compareTo).orElseThrow();
        var current = netLiquidityAt(walcl, tga, rrp, anchor);
        var prior4w = netLiquidityAt(walcl, tga, rrp, anchor.minusWeeks(4));
        var prior8w = netLiquidityAt(walcl, tga, rrp, anchor.minusWeeks(8));
        if (current == null || prior4w == null) return;

        var impulse4wBn = (current - prior4w) / 1_000d;
        var state = liquidityImpulseState(impulse4wBn);
        put(out, anchor, "NET_LIQUIDITY_LEVEL_TN", "net_liquidity_level_tn", current / 1_000_000d,
                "(WALCL - WDTGAL - RRPONTSYD*1000) / 1,000,000; USD trillions, analytical proxy");
        put(out, anchor, "NET_LIQUIDITY_IMPULSE_4W_BN", "net_liquidity_impulse_4w_bn", impulse4wBn,
                "Current net-liquidity proxy minus four-week-prior proxy; USD billions");
        put(out, anchor, "NET_LIQUIDITY_IMPULSE_STATE", "net_liquidity_impulse_state", state,
                "+/-2 at +/-100bn, +/-1 at +/-25bn, otherwise 0; heuristic condition, not return probability");

        var tgaNow = pointOnOrBefore(tga, anchor, 10);
        var tgaPrior = pointOnOrBefore(tga, anchor.minusWeeks(4), 10);
        if (tgaNow != null && tgaPrior != null) {
            var contribution = -(tgaNow.value() - tgaPrior.value()) / 1_000d;
            put(out, anchor, "TGA_LIQUIDITY_CONTRIBUTION_4W_BN", "tga_liquidity_contribution_4w_bn",
                    contribution, "Negative four-week TGA balance change; positive means current reserve injection (USD bn)");
            var issuanceIndicator = out.get("TREASURY_ISSUANCE_DIRECTION");
            var issuance = issuanceIndicator == null ? null : issuanceIndicator.value();
            // The context inherits a current weekly anchor. Without checking the
            // quarterly source date here, an old Z.1 observation would be
            // laundered into a fresh-looking derived value indefinitely.
            if (issuance != null && INPUT_FRESHNESS.usableRaw(
                    "TREASURY_MARKETABLE_ISSUANCE", issuanceIndicator.date(), anchor)) {
                var context = contribution >= 25 && issuance > 0 ? 1d : 0d;
                put(out, anchor, "TGA_LAGGED_ISSUANCE_CONTEXT", "tga_lagged_issuance_context", context,
                        "1 when current TGA injection >=25bn overlaps higher latest-released quarterly marketable-Treasury transactions; lagged historical context, not a refill or auction forecast");
                // Backward-compatible alias. New consumers must use TGA_LAGGED_ISSUANCE_CONTEXT.
                put(out, anchor, "TGA_ISSUANCE_OFFSET_RISK", "tga_issuance_offset_risk", context,
                        "Deprecated alias of TGA_LAGGED_ISSUANCE_CONTEXT; not evidence that current liquidity is offset");
            }
        }

        var rrpNow = pointOnOrBefore(rrp, anchor, 3);
        var rrpPrior = pointOnOrBefore(rrp, anchor.minusWeeks(4), 3);
        if (rrpNow != null && rrpPrior != null) {
            put(out, anchor, "RRP_LIQUIDITY_CONTRIBUTION_4W_BN", "rrp_liquidity_contribution_4w_bn",
                    -(rrpNow.value() - rrpPrior.value()),
                    "Negative four-week ON RRP balance change; positive means reserve-support direction (USD bn)");
            var threeYearStart = anchor.minusYears(3);
            var peak = rrp.stream().filter(point -> !point.date().isBefore(threeYearStart)
                            && !point.date().isAfter(anchor))
                    .mapToDouble(MarketSeriesPoint::value).max().orElse(0);
            if (peak > 0) {
                var pct = rrpNow.value() / peak * 100;
                put(out, anchor, "RRP_BUFFER_PCT_OF_3Y_PEAK", "rrp_buffer_pct_of_3y_peak", pct,
                        "Latest ON RRP balance / trailing three-year peak * 100; balance-based scope for further decline, not bank reserves");
                put(out, anchor, "RRP_BUFFER_LOW", "rrp_buffer_low",
                        rrpNow.value() <= 100 || pct <= 10 ? 1d : 0d,
                        "1 when ON RRP <=100bn or <=10% of its trailing three-year peak; future runoff support may be limited");
            }
        }

        if (prior8w == null) return;
        var priorImpulse4wBn = (prior4w - prior8w) / 1_000d;
        var acceleration = impulse4wBn - priorImpulse4wBn;
        var turn = impulse4wBn >= 25 && priorImpulse4wBn <= 0 ? 1d
                : impulse4wBn <= -25 && priorImpulse4wBn >= 0 ? -1d : 0d;
        put(out, anchor, "NET_LIQUIDITY_ACCELERATION_4W_BN", "net_liquidity_acceleration_4w_bn",
                acceleration, "Latest four-week net-liquidity impulse minus prior four-week impulse (USD bn)");
        put(out, anchor, "NET_LIQUIDITY_TURN_SIGNAL", "net_liquidity_turn_signal", turn,
                "+1/-1 when the latest non-overlapping four-week impulse changes sign versus the prior four-week window and exceeds +/-25bn; coarse event flag, not persistent buy/sell advice");
    }

    /**
     * A quarterly transactions flow may be positive, zero or negative, so a
     * percentage change can invert its economic sign around zero. Compare the
     * latest quarter with the prior four-quarter mean in USD billions and expose
     * a bounded direction state instead.
     */
    private static void treasuryIssuancePressure(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories
    ) {
        var points = history(histories, "FRED:TREASURY_MARKETABLE_ISSUANCE");
        if (points.size() < 5) return;
        var latestPoint = points.getLast();
        var latest = latestPoint.value();
        var prior = points.subList(points.size() - 5, points.size() - 1).stream()
                .mapToDouble(MarketSeriesPoint::value).average().orElseThrow();
        var deltaBn = (latest - prior) / 1_000d;
        var direction = deltaBn >= 50 ? 1d : deltaBn <= -50 ? -1d : 0d;
        put(out, latestPoint.date(), "TREASURY_NET_ISSUANCE_CHANGE_BN", "treasury_net_issuance_change_bn", deltaBn,
                "Latest quarterly marketable Treasury liability transactions minus prior four-quarter mean (USD bn)");
        put(out, latestPoint.date(), "TREASURY_ISSUANCE_DIRECTION", "treasury_issuance_direction", direction,
                "+1 when change >=50bn, -1 when <=-50bn, else 0; flow-safe heuristic, not an auction forecast");
    }

    private static Double liquidityImpulseState(double impulseBn) {
        return impulseBn >= 100 ? 2d : impulseBn >= 25 ? 1d
                : impulseBn <= -100 ? -2d : impulseBn <= -25 ? -1d : 0d;
    }

    private static Double netLiquidityAt(
            List<MarketSeriesPoint> walcl,
            List<MarketSeriesPoint> tga,
            List<MarketSeriesPoint> rrp,
            LocalDate target
    ) {
        var assets = pointOnOrBefore(walcl, target, 10);
        var treasury = pointOnOrBefore(tga, target, 10);
        var reverseRepo = pointOnOrBefore(rrp, target, 3);
        if (assets == null || treasury == null || reverseRepo == null) return null;
        return assets.value() - treasury.value() - reverseRepo.value() * 1_000d;
    }

    private static MarketSeriesPoint pointOnOrBefore(
            List<MarketSeriesPoint> points,
            LocalDate target,
            int maximumGapDays
    ) {
        MarketSeriesPoint candidate = null;
        for (var point : points) {
            if (point.date().isAfter(target)) break;
            candidate = point;
        }
        if (candidate == null || ChronoUnit.DAYS.between(candidate.date(), target) > maximumGapDays) return null;
        return candidate;
    }

    private static void liquidityTransmission(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            LocalDate asOf
    ) {
        var available = 0;
        var stressed = 0;
        var credit = value(out, "CREDIT_STRESS_FLAG");
        if (credit != null) { available++; if (credit >= 1) stressed++; }
        var vix = raw.get("VIXCLS");
        if (vix != null) { available++; if (vix >= 30) stressed++; }
        var funding = value(out, "SOFR_IORB_SPREAD");
        if (funding != null) { available++; if (funding >= .10) stressed++; }
        if (available == 0) return;
        put(out, asOf, "LIQUIDITY_TRANSMISSION_STRESS_SCORE", "liquidity_transmission_stress_score",
                (double) stressed,
                "Count of credit-stress flag, VIX>=30 and SOFR-IORB>=0.10%p; deleveraging gate, not probability");
        put(out, asOf, "LIQUIDITY_TRANSMISSION_COVERAGE", "liquidity_transmission_coverage",
                available * 100d / 3,
                "Available credit, volatility and funding-stress axes / three * 100");
    }

    private static void liquiditySpillover(
            Map<String, CoreDerivedIndicator> out,
            Map<String, Double> raw,
            LocalDate asOf
    ) {
        var axes = new ArrayList<Double>();
        var plumbing = value(out, "LIQUIDITY_PLUMBING_SIGNAL");
        if (plumbing != null) axes.add(Math.signum(plumbing));
        var dollar = value(out, "DXY_TREND");
        if (dollar != null) axes.add(Math.abs(dollar) < 0.15 ? 0d : -Math.signum(dollar));
        var usdkrw = raw.get("USDKRW");
        if (usdkrw != null) axes.add(usdkrw <= 1_480 ? 1d : usdkrw >= 1_500 ? -1d : 0d);
        var foreignFlow = value(out, "KOSPI_FOREIGN_TREND");
        if (foreignFlow != null) axes.add(Math.abs(foreignFlow) < 500 ? 0d : Math.signum(foreignFlow));
        if (axes.isEmpty()) return;
        var signed = axes.stream().mapToDouble(Double::doubleValue).sum();
        var score = clamp(50 + signed * 50 / axes.size(), 0, 100);
        var signal = signed >= 3 ? 2d : signed >= 1 ? 1d : signed <= -3 ? -2d : signed <= -1 ? -1d : 0d;
        put(out, asOf, "DOLLAR_LIQUIDITY_SPILLOVER_SCORE", "dollar_liquidity_spillover_score", score,
                "US liquidity plumbing + inverse DXY trend + USDKRW gate + KOSPI foreign-flow trend (0..100)");
        put(out, asOf, "DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL", "dollar_liquidity_spillover_signal", signal,
                "+2/+1=dollar liquidity is reaching EM/Korea, 0=mixed, -1/-2=capital-reversal pressure");
        put(out, asOf, "DOLLAR_LIQUIDITY_SPILLOVER_CONFIDENCE", "dollar_liquidity_spillover_confidence",
                axes.size() * 25d, "Available spillover axes / four * 100; not a forecast probability");
    }

    private static void realYieldTrend(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf
    ) {
        var nominal = history(histories, "FRED:DGS10");
        var inflation = history(histories, "FRED:T10YIE");
        if (nominal.size() < 20 || inflation.size() < 20) return;
        var nRecent = averageLastValues(nominal, 5, 0);
        var nPrior = averageLastValues(nominal, 5, 15);
        var iRecent = averageLastValues(inflation, 5, 0);
        var iPrior = averageLastValues(inflation, 5, 15);
        if (nRecent != null && nPrior != null && iRecent != null && iPrior != null) {
            put(out, asOf, "REAL_YIELD_TREND", "real_yield_trend",
                    (nRecent - iRecent) - (nPrior - iPrior),
                    "Recent 5-point real yield - 15~20-point-prior real yield");
        }
    }

    private static void direction(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            String key,
            String name,
            String historyKey,
            int recentWindow,
            int priorWindow,
            String formula
    ) {
        var points = history(histories, historyKey);
        if (points.size() < recentWindow + priorWindow) return;
        var values = points.stream().map(MarketSeriesPoint::value).toList();
        var recent = average(values.subList(values.size() - recentWindow, values.size()));
        var prior = average(values.subList(values.size() - recentWindow - priorWindow, values.size() - recentWindow));
        put(out, points.getLast().date(), key, name, prior == 0 ? null : percent(recent, prior), formula);
    }

    private static void trendDifference(
            Map<String, CoreDerivedIndicator> out,
            Map<String, List<MarketSeriesPoint>> histories,
            LocalDate asOf,
            String key,
            String name,
            String historyKey,
            int recentWindow,
            int priorOffset,
            int priorWindow,
            String formula
    ) {
        var points = history(histories, historyKey);
        if (points.size() < priorOffset + priorWindow) return;
        var values = points.stream().map(MarketSeriesPoint::value).toList();
        var recent = average(values.subList(values.size() - recentWindow, values.size()));
        var priorEnd = values.size() - priorOffset;
        var prior = average(values.subList(priorEnd - priorWindow, priorEnd));
        put(out, asOf, key, name, recent - prior, formula);
    }

    private static Double yearOverYear(List<MarketSeriesPoint> points) {
        if (points.size() < 2) return null;
        var latest = points.getLast();
        var target = latest.date().minusYears(1);
        MarketSeriesPoint past = null;
        for (var point : points) {
            if (!point.date().isAfter(target)) past = point;
            else break;
        }
        if (past == null || past.value() == 0
                || Math.abs(ChronoUnit.DAYS.between(past.date(), target)) > 45) return null;
        return percent(latest.value(), past.value());
    }

    private static Double returnPct(List<MarketSeriesPoint> points, int lookback) {
        if (points.size() <= lookback) return null;
        var current = points.getLast().value();
        var prior = points.get(points.size() - 1 - lookback).value();
        return prior <= 0 ? null : percent(current, prior);
    }

    private static Double returnPctBetween(
            List<MarketSeriesPoint> points,
            int startLookback,
            int endLag
    ) {
        if (points.size() <= startLookback || startLookback <= endLag) return null;
        var end = points.get(points.size() - 1 - endLag).value();
        var start = points.get(points.size() - 1 - startLookback).value();
        return start <= 0 ? null : percent(end, start);
    }

    /**
     * Computes relative strength from the investable price ratio rather than
     * subtracting two independently sampled returns. Intersecting by date keeps
     * holidays and provider gaps from shifting the sector and benchmark legs by
     * one session.
     */
    private static Double relativeReturnPct(
            List<MarketSeriesPoint> sector,
            List<MarketSeriesPoint> benchmark,
            int lookback
    ) {
        return relativeReturnPctBetween(sector, benchmark, lookback, 0);
    }

    private static Double relativeReturnPctBetween(
            List<MarketSeriesPoint> sector,
            List<MarketSeriesPoint> benchmark,
            int startLookback,
            int endLag
    ) {
        var ratios = alignedRelativeRatios(sector, benchmark);
        if (ratios.size() <= startLookback || startLookback <= endLag) return null;
        var end = ratios.get(ratios.size() - 1 - endLag);
        var start = ratios.get(ratios.size() - 1 - startLookback);
        return start <= 0 ? null : percent(end, start);
    }

    private static Double relativeVolatility(
            List<MarketSeriesPoint> sector,
            List<MarketSeriesPoint> benchmark,
            int window,
            int endLag
    ) {
        var ratios = alignedRelativeRatios(sector, benchmark);
        var end = ratios.size() - endLag;
        var start = end - window;
        if (start < 1 || end > ratios.size()) return null;
        var returns = new ArrayList<Double>(window);
        for (var index = start; index < end; index++) {
            var prior = ratios.get(index - 1);
            var current = ratios.get(index);
            if (prior <= 0 || current <= 0) return null;
            returns.add(Math.log(current / prior));
        }
        var mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        var variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(252d);
    }

    private static List<Double> alignedRelativeRatios(
            List<MarketSeriesPoint> sector,
            List<MarketSeriesPoint> benchmark
    ) {
        if (sector.isEmpty() || benchmark.isEmpty()) return List.of();
        var benchmarkByDate = new LinkedHashMap<LocalDate, Double>();
        benchmark.forEach(point -> benchmarkByDate.put(point.date(), point.value()));
        var ratios = new ArrayList<Double>();
        for (var point : sector) {
            var benchmarkValue = benchmarkByDate.get(point.date());
            if (point.value() > 0 && benchmarkValue != null && benchmarkValue > 0) {
                ratios.add(point.value() / benchmarkValue);
            }
        }
        return List.copyOf(ratios);
    }

    private static double crossSectionalPercentile(Map<String, Double> values, String key) {
        var current = values.get(key);
        if (current == null || values.size() < 2) return 50;
        var below = values.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(key) && entry.getValue() < current)
                .count();
        var equal = values.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(key)
                        && Double.compare(entry.getValue(), current) == 0)
                .count();
        return (below + equal * .5) * 100d / (values.size() - 1d);
    }

    private static Double rsi(List<Double> closes, int period) {
        if (closes.size() <= period) return null;
        double gains = 0;
        double losses = 0;
        for (int index = closes.size() - period; index < closes.size(); index++) {
            var change = closes.get(index) - closes.get(index - 1);
            if (change > 0) gains += change;
            else losses -= change;
        }
        if (losses == 0) return gains == 0 ? 50d : 100d;
        var relativeStrength = (gains / period) / (losses / period);
        return 100 - (100 / (1 + relativeStrength));
    }

    private static Double averageLast(List<Double> values, int count) {
        if (values.size() < count) return null;
        return average(values.subList(values.size() - count, values.size()));
    }

    private static Double averageLastValues(List<MarketSeriesPoint> points, int count, int offset) {
        var end = points.size() - offset;
        if (end < count) return null;
        return points.subList(end - count, end).stream().mapToDouble(MarketSeriesPoint::value).average().orElse(0);
    }

    private static Double convergencePct(Double... values) {
        var finite = java.util.Arrays.stream(values).filter(java.util.Objects::nonNull).toList();
        if (finite.size() < 3) return null;
        var minimum = finite.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        var maximum = finite.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        var mean = finite.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return mean == 0 ? null : (maximum - minimum) / mean * 100;
    }

    private static CloseChannel closeChannel(List<Double> closes, int length) {
        if (length < 60) return null;
        var start = closes.size() - length;
        var meanX = (length - 1) / 2d;
        var meanY = 0d;
        for (var index = 0; index < length; index++) meanY += Math.log(closes.get(start + index));
        meanY /= length;
        var numerator = 0d;
        var denominator = 0d;
        for (var index = 0; index < length; index++) {
            var x = index - meanX;
            numerator += x * (Math.log(closes.get(start + index)) - meanY);
            denominator += x * x;
        }
        if (denominator == 0) return null;
        var slope = numerator / denominator;
        var intercept = meanY - slope * meanX;
        var residualSquares = 0d;
        for (var index = 0; index < length; index++) {
            var residual = Math.log(closes.get(start + index)) - (intercept + slope * index);
            residualSquares += residual * residual;
        }
        var deviation = Math.sqrt(residualSquares / Math.max(1, length - 2));
        var expected = intercept + slope * (length - 1);
        var lower = Math.exp(expected - 2 * deviation);
        var mid = Math.exp(expected);
        var upper = Math.exp(expected + 2 * deviation);
        var spread = upper - lower;
        var position = spread <= 0 ? 50d : (closes.getLast() - lower) / spread * 100;
        return new CloseChannel(
                lower, mid, upper, clamp(position, -25, 125),
                (Math.exp(slope * 252) - 1) * 100
        );
    }

    private static List<CloseSwing> closeSwings(List<Double> closes, int window) {
        var result = new ArrayList<CloseSwing>();
        for (var index = window; index < closes.size() - window; index++) {
            var current = closes.get(index);
            var high = true;
            var low = true;
            for (var offset = -window; offset <= window; offset++) {
                if (offset == 0) continue;
                if (closes.get(index + offset) >= current) high = false;
                if (closes.get(index + offset) <= current) low = false;
            }
            if (high) result.add(new CloseSwing(index, current, true));
            if (low) result.add(new CloseSwing(index, current, false));
        }
        return List.copyOf(result);
    }

    private static List<CloseZone> closeZones(List<CloseSwing> swings, int firstIndex) {
        var clusters = new ArrayList<MutableCloseZone>();
        for (var swing : swings) {
            if (swing.index() < firstIndex) continue;
            var match = clusters.stream()
                    .filter(value -> Math.abs(swing.value() / value.center() - 1) <= .025)
                    .min(Comparator.comparingDouble(value -> Math.abs(swing.value() - value.center())))
                    .orElse(null);
            if (match == null) {
                match = new MutableCloseZone();
                clusters.add(match);
            }
            match.add(swing.value());
        }
        return clusters.stream()
                .filter(value -> value.count >= 2)
                .map(value -> new CloseZone(value.center(), value.count))
                .toList();
    }

    private static int closeRangeDuration(List<Double> closes) {
        for (var days = Math.min(120, closes.size()); days >= 20; days--) {
            var slice = closes.subList(closes.size() - days, closes.size());
            var minimum = slice.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            var maximum = slice.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (minimum <= 0) continue;
            var range = percent(maximum, minimum);
            var allowed = days >= 90 ? 18 : days >= 60 ? 15 : days >= 40 ? 12 : 9;
            var net = Math.abs(percent(slice.getLast(), slice.getFirst()));
            if (range <= allowed && net <= allowed * .55) return days;
        }
        return 0;
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static Double weighted(Object... valuesAndWeights) {
        double sum = 0;
        double weight = 0;
        for (int index = 0; index < valuesAndWeights.length; index += 2) {
            var value = (Double) valuesAndWeights[index];
            var itemWeight = (Double) valuesAndWeights[index + 1];
            if (value != null) {
                sum += value * itemWeight;
                weight += itemWeight;
            }
        }
        return weight == 0 ? null : sum / weight;
    }

    private static List<MarketSeriesPoint> history(
            Map<String, List<MarketSeriesPoint>> histories,
            String key
    ) {
        return histories.getOrDefault(key, List.of()).stream()
                .sorted(Comparator.comparing(MarketSeriesPoint::date))
                .toList();
    }

    private static void ratio(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String key,
            String name,
            Double numerator,
            Double denominator,
            boolean divide,
            String formula
    ) {
        if (numerator == null || denominator == null || (divide && denominator == 0)) return;
        put(out, asOf, key, name, divide ? numerator / denominator : numerator - denominator, formula);
    }

    private static void difference(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String key,
            String name,
            Double left,
            Double right,
            String formula
    ) {
        if (left != null && right != null) put(out, asOf, key, name, left - right, formula);
    }

    private static void put(
            Map<String, CoreDerivedIndicator> out,
            LocalDate asOf,
            String key,
            String name,
            Double value,
            String formula
    ) {
        if (value != null) value = round(value, 6);
        out.put(key, new CoreDerivedIndicator(key, name, value, asOf, formula));
    }

    private static Double value(Map<String, CoreDerivedIndicator> values, String key) {
        var indicator = values.get(key);
        return indicator == null ? null : indicator.value();
    }

    private static int pricingScore(Double value) {
        if (value == null) return 0;
        if (value >= 1.5 && value <= 2.5) return 2;
        if (value >= 1 && value <= 3.5) return 1;
        if (value < 0 || value > 5) return -2;
        return -1;
    }

    private static int ismScore(Double value) {
        if (value == null) return 0;
        if (value >= 50 && value <= 55) return 2;
        if (value >= 48 && value <= 60) return 1;
        if (value < 42 || value > 65) return -2;
        return -1;
    }

    private static int unemploymentScore(Double value) {
        if (value == null) return 0;
        if (value < 4) return 2;
        if (value < 5) return 1;
        if (value < 6) return 0;
        if (value < 7) return -1;
        return -2;
    }

    private static double percent(double current, double previous) {
        return previous == 0 ? 0 : ((current / previous) - 1) * 100;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double round(double value, int scale) {
        var factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    private record CloseChannel(
            double lower,
            double mid,
            double upper,
            double positionPct,
            double annualizedSlopePct
    ) {
    }

    private record CloseSwing(int index, double value, boolean high) {
    }

    private record MarketFibSwing(CloseSwing start, CloseSwing end, double amplitudePct) {
    }

    private record CloseZone(double center, int touches) {
    }

    private static final class MutableCloseZone {
        private double total;
        private int count;

        private void add(double value) {
            total += value;
            count++;
        }

        private double center() {
            return count == 0 ? 0 : total / count;
        }
    }
}
