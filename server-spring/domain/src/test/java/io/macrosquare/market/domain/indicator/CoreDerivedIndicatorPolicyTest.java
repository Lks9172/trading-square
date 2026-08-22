package io.macrosquare.market.domain.indicator;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoreDerivedIndicatorPolicyTest {

    private final CoreDerivedIndicatorPolicy policy = new CoreDerivedIndicatorPolicy();

    @Test
    void publishesDailyAndWeeklyMacdEvidenceWithoutOverwritingTheExistingSmaCross() {
        var histories = Map.of(
                "YAHOO:NASDAQ", points(280, index -> index < 180
                        ? 100 + index * .2
                        : 136 + (index - 180) * .8),
                "YAHOO:GOLD", points(280, index -> 2_000 + index * 2.5)
        );

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertTrue(result.containsKey("NASDAQ_MACD_LINE"));
        assertTrue(result.containsKey("NASDAQ_MACD_SIGNAL"));
        assertTrue(result.containsKey("NASDAQ_MACD_HISTOGRAM"));
        assertTrue(result.containsKey("NASDAQ_MACD_CROSS"));
        assertTrue(result.containsKey("NASDAQ_MACD_DIVERGENCE_ACTIVE"));
        assertTrue(result.containsKey("NASDAQ_WEEKLY_MACD_POSITION"));
        assertTrue(result.containsKey("GOLD_MACD_POSITION"));
        assertTrue(result.get("NASDAQ_MACD_CROSS").formula().contains("signal-line"));
        assertFalse(result.get("NASDAQ_MACD_CROSS").formula().contains("SMA50"));
    }

    @Test
    void derivesExactTwentyDayKrxFlowAndFxReversalEvidence() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("KRX:KOSPI_FOREIGN_NET_1D", points(20, index -> index < 15 ? -1_000 : 6_000));
        histories.put("KRX:KOSPI_INDIVIDUAL_NET_1D", points(20, index -> index < 15 ? 1_000 : -7_000));
        histories.put("KRX:KOSPI_INSTITUTION_NET_1D", points(20, ignored -> 100));
        histories.put("KRX:KOSPI_PENSION_NET_1D", points(20, index -> index < 15 ? 0 : 2_500));
        histories.put("YAHOO:USDKRW", points(5, ignored -> 1_470));

        var result = policy.evaluate(Map.of("USDKRW", 1_470d), histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "KOSPI_FOREIGN_NET_1D", 6_000);
        assertValue(result, "KOSPI_FOREIGN_NET_5D", 30_000);
        assertValue(result, "KOSPI_FOREIGN_NET_20D", 15_000);
        assertValue(result, "KOSPI_FOREIGN_TREND", 7_000);
        assertValue(result, "KOSPI_FOREIGN_BUY_STREAK", 5);
        assertValue(result, "KOSPI_FOREIGN_STREAK_DAYS", 5);
        assertValue(result, "KOSPI_INSTITUTION_NET_5D", 500);
        assertValue(result, "KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE", -1);
        assertValue(result, "KOSPI_PENSION_NET_5D", 12_500);
        assertValue(result, "KRX_PENSION_FUND_FLOW", 1);
        assertValue(result, "FX_FOREIGN_COMBO_ALERT", -1);
        assertValue(result, "KRW_FX_REVERSAL_TRIGGER", 1);
        assertEquals(LocalDate.parse("2026-01-20"), result.get("KOSPI_FOREIGN_NET_20D").date());
    }

    @Test
    void requiresFullSixtyDayEvidenceBeforeDeclaringPanicSellingAndFxGap() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("KRX:KOSPI_FOREIGN_NET_1D", points(60, ignored -> -6_000));
        histories.put("KRX:KOSPI_INDIVIDUAL_NET_1D", points(60, ignored -> 6_000));
        histories.put("KRX:KOSPI_INSTITUTION_NET_1D", points(60, ignored -> 0));
        histories.put("KRX:KOSPI_PENSION_NET_1D", points(60, ignored -> 0));
        histories.put("YAHOO:USDKRW", points(60, index -> 1_400 + (140d * index / 59d)));

        var result = policy.evaluate(Map.of("USDKRW", 1_510d), histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "KOSPI_FOREIGN_NET_20D", -120_000);
        assertValue(result, "KOSPI_FOREIGN_EXTREME", -1);
        assertValue(result, "KOSPI_FOREIGN_HISTORIC_EXTREME", 0);
        assertValue(result, "KOSPI_FOREIGN_SELL_STREAK", 60);
        assertValue(result, "KOSPI_FOREIGN_STREAK_DAYS", -60);
        assertValue(result, "KOSPI_FOREIGN_OVERSELL_30T_FLAG", 1);
        assertValue(result, "FX_FOREIGN_BASELINE_GAP_TRILLION", 1);
        assertValue(result, "FX_FOREIGN_COMBO_ALERT", 2);
    }

    @Test
    void derivesFiscalStressAndBondVigilanteFromHistoryInsteadOfAnAbsoluteRateShortcut() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("FRED:DGS30", points(20, index -> 4.5 + (.5 * index / 19d)));
        histories.put("YAHOO:WTI", points(61, index -> 100 + (20d * index / 60d)));
        var raw = Map.of(
                "DGS30", 5d,
                "DGS10", 4.4d,
                "T10Y2Y", .2d,
                "DXY", 99d,
                "BAMLH0A0HYM2", 5.5d
        );

        var result = policy.evaluate(raw, histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "DGS30_20D_CHANGE", .5);
        assertValue(result, "FISCAL_STRESS", 1);
        assertValue(result, "FISCAL_STRESS_HARD", 1);
        assertValue(result, "BOND_VIGILANTE_SCORE", 4);
        assertValue(result, "BOND_VIGILANTE_WARNING", 1);
        assertValue(result, "WTI_60D_CHANGE", 20);
        assertValue(result, "CPI_OIL_LAG_PRESSURE", 2);
        assertValue(result, "STAGFLATION_SCORE", 1);
    }

    @Test
    void derivesFreshDowChannelSupportAndRsiConfluenceInputsForIndexesAndCrypto() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("YAHOO:NASDAQ", points(280,
                index -> 100 + index * .25 + Math.sin(index * Math.PI * 2 / 30) * 4));
        histories.put("YAHOO:BTC", points(280,
                index -> 20_000 + index * 60 + Math.sin(index * Math.PI * 2 / 26) * 900));

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertTrue(result.get("NASDAQ_STRUCTURE_SCORE").value() >= 0);
        assertValue(result, "NASDAQ_DOW_TREND_STATE", 1);
        assertTrue(result.get("NASDAQ_STRUCTURE_CHANNEL_LOWER").value()
                < result.get("NASDAQ_STRUCTURE_CHANNEL_MID").value());
        assertTrue(result.get("NASDAQ_STRUCTURE_CHANNEL_MID").value()
                < result.get("NASDAQ_STRUCTURE_CHANNEL_UPPER").value());
        assertTrue(result.containsKey("NASDAQ_DMA_CONVERGENCE_PCT"));
        assertTrue(result.containsKey("NASDAQ_BEARISH_REVERSAL_STAGE"));
        assertTrue(result.containsKey("NASDAQ_RSI_SUPPORT_CONFLUENCE"));
        assertTrue(result.containsKey("NASDAQ_FIB_236"));
        assertTrue(result.containsKey("NASDAQ_FIB_382"));
        assertTrue(result.containsKey("NASDAQ_FIB_500"));
        assertTrue(result.containsKey("NASDAQ_FIB_618"));
        assertTrue(result.containsKey("NASDAQ_FIB_786"));
        assertTrue(result.containsKey("NASDAQ_FIB_CURRENT_RETRACEMENT"));
        assertTrue(result.containsKey("NASDAQ_FIB_CONFLUENCE_SCORE"));
        assertTrue(result.containsKey("BTC_STRUCTURE_SCORE"));
        assertEquals(LocalDate.parse("2026-07-31"), result.get("NASDAQ_STRUCTURE_SCORE").date());
    }

    @Test
    void derivesFullyAlignedCurrentThreeAxisLiquidityPlumbingWithoutTreatingItAsProbability() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("FRED:RRPONTSYD", points(10, index -> index < 5 ? 100 : 80));
        histories.put("FRED:WDTGAL", points(4, index -> index < 2 ? 100 : 80));
        histories.put("FRED:WRESBAL", points(4, index -> index < 2 ? 100 : 110));
        histories.put("FRED:TREASURY_MARKETABLE_ISSUANCE", points(5, index -> index < 4 ? 100_000 : 50_000));

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "LIQUIDITY_PLUMBING_SCORE", 100);
        assertValue(result, "LIQUIDITY_PLUMBING_SIGNAL", 2);
        assertValue(result, "LIQUIDITY_PLUMBING_BULLISH_AXES", 3);
        assertValue(result, "LIQUIDITY_PLUMBING_BEARISH_AXES", 0);
        assertValue(result, "LIQUIDITY_PLUMBING_NEUTRAL_AXES", 0);
        assertValue(result, "LIQUIDITY_PLUMBING_CONFIDENCE", 100);
        assertTrue(result.get("LIQUIDITY_PLUMBING_CONFIDENCE").formula().contains("not a return probability"));
        assertTrue(result.get("LIQUIDITY_PLUMBING_SCORE").formula().contains("not independent factors"));
        assertEquals(LocalDate.parse("2026-01-04"), result.get("LIQUIDITY_PLUMBING_SIGNAL").date());
    }

    @Test
    void exposesTheCurrentReserveBalanceLevelWithoutCallingThreeTrillionAnOfficialSafetyLine() {
        var history = List.of(
                new MarketSeriesPoint(LocalDate.parse("2026-08-05"), 3_010_000),
                new MarketSeriesPoint(LocalDate.parse("2026-08-12"), 2_944_059)
        );

        var result = policy.evaluate(Map.of(), Map.of("FRED:WRESBAL", history),
                LocalDate.parse("2026-08-16"));

        assertValue(result, "WRESBAL_LEVEL_TN", 2.944059);
        assertValue(result, "WRESBAL_ABSOLUTE_LEVEL", 0);
        assertEquals(LocalDate.parse("2026-08-12"), result.get("WRESBAL_LEVEL_TN").date());
        assertTrue(result.get("WRESBAL_ABSOLUTE_LEVEL").formula().contains("not an official safety boundary"));
    }

    @Test
    void derivesPointInTimeNetLiquidityImpulseTurnAndRrpRunwayWithCorrectUnits() {
        var dates = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> LocalDate.parse("2026-06-03").plusWeeks(index)).toList();
        var walcl = new ArrayList<MarketSeriesPoint>();
        var tga = new ArrayList<MarketSeriesPoint>();
        var rrp = new ArrayList<MarketSeriesPoint>();
        for (var index = 0; index < dates.size(); index++) {
            walcl.add(new MarketSeriesPoint(dates.get(index), index == 8 ? 7_100_000 : 7_000_000));
            tga.add(new MarketSeriesPoint(dates.get(index), index == 8 ? 500_000 : 550_000));
            rrp.add(new MarketSeriesPoint(dates.get(index), 100));
        }
        var histories = Map.of(
                "FRED:WALCL", List.copyOf(walcl),
                "FRED:WDTGAL", List.copyOf(tga),
                "FRED:RRPONTSYD", List.copyOf(rrp),
                "FRED:TREASURY_MARKETABLE_ISSUANCE",
                points(5, index -> index < 4 ? 100_000 : 150_000)
        );

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "NET_LIQUIDITY_LEVEL_TN", 6.5);
        assertValue(result, "NET_LIQUIDITY_IMPULSE_4W_BN", 150);
        assertValue(result, "NET_LIQUIDITY_ACCELERATION_4W_BN", 150);
        assertValue(result, "NET_LIQUIDITY_IMPULSE_STATE", 2);
        assertValue(result, "NET_LIQUIDITY_TURN_SIGNAL", 1);
        assertValue(result, "RRP_BUFFER_PCT_OF_3Y_PEAK", 100);
        assertValue(result, "RRP_BUFFER_LOW", 1);
        assertValue(result, "TGA_LAGGED_ISSUANCE_CONTEXT", 1);
        assertValue(result, "TGA_ISSUANCE_OFFSET_RISK", 1);
        assertTrue(result.get("TGA_LAGGED_ISSUANCE_CONTEXT").formula().contains("not a refill or auction forecast"));
        assertValue(result, "LIQUIDITY_DIRECTION", 2);
        assertEquals(LocalDate.parse("2026-07-29"), result.get("NET_LIQUIDITY_IMPULSE_4W_BN").date());
    }

    @Test
    void keepsLaggedIssuancePressureOutOfTheCurrentLiquidityAlignment() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("FRED:RRPONTSYD", points(10, index -> index < 5 ? 100 : 80));
        histories.put("FRED:WDTGAL", points(4, index -> index < 2 ? 100 : 80));
        histories.put("FRED:WRESBAL", points(4, index -> index < 2 ? 100 : 110));
        histories.put("FRED:TREASURY_MARKETABLE_ISSUANCE", points(5, index -> index < 4 ? 100_000 : 150_000));

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "LIQUIDITY_PLUMBING_SIGNAL", 2);
        assertValue(result, "LIQUIDITY_PLUMBING_BULLISH_AXES", 3);
        assertValue(result, "LIQUIDITY_PLUMBING_BEARISH_AXES", 0);
        assertValue(result, "LIQUIDITY_PLUMBING_NEUTRAL_AXES", 0);
        assertValue(result, "TREASURY_NET_ISSUANCE_CHANGE_BN", 50);
        assertValue(result, "TREASURY_ISSUANCE_DIRECTION", 1);
        assertEquals(LocalDate.parse("2026-01-05"),
                result.get("TREASURY_NET_ISSUANCE_CHANGE_BN").date());
        assertEquals(LocalDate.parse("2026-01-05"),
                result.get("TREASURY_ISSUANCE_DIRECTION").date());
    }

    @Test
    void neverLaundersAnExpiredQuarterlyIssuanceObservationIntoCurrentTgaContext() {
        var weeklyDates = java.util.stream.IntStream.range(0, 9)
                .mapToObj(index -> LocalDate.parse("2026-06-03").plusWeeks(index)).toList();
        var walcl = weeklyDates.stream().map(date -> new MarketSeriesPoint(date, 7_000_000)).toList();
        var tga = new ArrayList<MarketSeriesPoint>();
        var rrp = new ArrayList<MarketSeriesPoint>();
        for (var index = 0; index < weeklyDates.size(); index++) {
            tga.add(new MarketSeriesPoint(weeklyDates.get(index), index < 5 ? 600_000 : 500_000));
            rrp.add(new MarketSeriesPoint(weeklyDates.get(index), 100));
        }
        var staleIssuance = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new MarketSeriesPoint(
                        LocalDate.parse("2024-01-01").plusMonths(index * 3L),
                        index < 4 ? 100_000 : 200_000))
                .toList();

        var result = policy.evaluate(Map.of(), Map.of(
                "FRED:WALCL", walcl,
                "FRED:WDTGAL", List.copyOf(tga),
                "FRED:RRPONTSYD", List.copyOf(rrp),
                "FRED:TREASURY_MARKETABLE_ISSUANCE", staleIssuance
        ), LocalDate.parse("2026-07-31"));

        assertValue(result, "TREASURY_ISSUANCE_DIRECTION", 1);
        assertValue(result, "TGA_LIQUIDITY_CONTRIBUTION_4W_BN", 100);
        assertFalse(result.containsKey("TGA_LAGGED_ISSUANCE_CONTEXT"));
        assertFalse(result.containsKey("TGA_ISSUANCE_OFFSET_RISK"));
    }

    @Test
    void doesNotUsePercentageChangeForAnIssuanceFlowThatCrossesZero() {
        var histories = Map.of("FRED:TREASURY_MARKETABLE_ISSUANCE",
                points(5, index -> index < 4 ? -100_000 : 100_000));

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "TREASURY_NET_ISSUANCE_CHANGE_BN", 200);
        assertValue(result, "TREASURY_ISSUANCE_DIRECTION", 1);
    }

    @Test
    void separatesLaggedM2LevelFromItsRecentDirectionAndSpeed() {
        var m2 = new ArrayList<MarketSeriesPoint>();
        for (var index = 0; index < 13; index++) {
            var value = index < 9 ? 100d : 100d + (index - 9);
            m2.add(new MarketSeriesPoint(LocalDate.parse("2025-07-01").plusMonths(index), value));
        }

        var result = policy.evaluate(Map.of(), Map.of("FRED:M2SL", List.copyOf(m2)),
                LocalDate.parse("2026-07-31"));

        assertValue(result, "US_M2_YOY", 3);
        assertValue(result, "US_M2_3M_ANNUALIZED", (Math.pow(1.03, 4) - 1) * 100);
        assertValue(result, "US_M2_GROWTH_ACCELERATION", (Math.pow(1.03, 4) - 1) * 100 - 3);
        assertTrue(result.get("US_M2_3M_ANNUALIZED").formula().contains("lagged"));
    }

    @Test
    void derivesActualTenObservationBasketPutCallAndCoverageWithoutPretendingItIsProbability() {
        var histories = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        histories.put("SENTIMENT:PC_RATIO", points(60, index -> .70 + index * .01));
        var raw = Map.of(
                "FEAR_GREED", 25d,
                "AAII_BULL_BEAR_SPREAD", -10d,
                "NAAIM_EXPOSURE", 40d
        );

        var result = policy.evaluate(raw, histories, LocalDate.parse("2026-07-31"));

        assertValue(result, "PC_RATIO_10D", 1.245);
        assertValue(result, "PC_RATIO_HISTORY_COUNT", 60);
        assertTrue(result.containsKey("PC_RATIO_10D_PERCENTILE"));
        assertValue(result, "PSYCH_SUBSCORE_COVERAGE", 100);
        assertTrue(result.get("PSYCH_SUBSCORE").formula().contains("not return probability"));
    }

    @Test
    void doesNotCallAOneDayPutCallReadingTenDayOrCreatePsychologyFromOneComponent() {
        var histories = Map.of("SENTIMENT:PC_RATIO", points(1, ignored -> 1.4));

        var result = policy.evaluate(Map.of("FEAR_GREED", 10d), histories, LocalDate.parse("2026-07-31"));

        assertFalse(result.containsKey("PC_RATIO_10D"));
        assertFalse(result.containsKey("PSYCH_SUBSCORE"));
        assertValue(result, "PSYCH_SUBSCORE_COVERAGE", 25);
    }

    @Test
    void doesNotCallAnArbitrarilyOldObservationYearOverYearEvidence() {
        var histories = Map.of("FRED:M2SL", List.of(
                new MarketSeriesPoint(LocalDate.parse("2024-01-01"), 100),
                new MarketSeriesPoint(LocalDate.parse("2026-06-01"), 110)
        ));

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertNull(result.get("US_M2_YOY").value());
        assertFalse(result.containsKey("GLOBAL_M2_PROXY"));
    }

    @Test
    void sectorRelativeStrengthUsesTheDateAlignedSectorToSp500Ratio() {
        var benchmark = points(300, index -> 100 + index * .2);
        var sector = points(300, index -> (100 + index * .2) * (1 + index * .001));
        var histories = Map.of(
                "YAHOO:SP500", benchmark,
                "YAHOO:XLK", sector
        );

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        var expectedOneMonth = ((1 + 299 * .001) / (1 + (299 - 21) * .001) - 1) * 100;
        var expectedThreeMonth = ((1 + 299 * .001) / (1 + (299 - 63) * .001) - 1) * 100;
        var expectedSixMonth = ((1 + 299 * .001) / (1 + (299 - 126) * .001) - 1) * 100;
        var expectedTwelveMonth = ((1 + 299 * .001) / (1 + (299 - 252) * .001) - 1) * 100;
        var expectedSixExRecent = ((1 + (299 - 21) * .001) / (1 + (299 - 147) * .001) - 1) * 100;
        var expectedTwelveExRecent = ((1 + (299 - 21) * .001) / (1 + (299 - 273) * .001) - 1) * 100;
        var expectedCurrent = (expectedSixExRecent + expectedTwelveExRecent) / 2;
        assertValue(result, "SECTOR_REL_1M_XLK", expectedOneMonth);
        assertValue(result, "SECTOR_REL_3M_XLK", expectedThreeMonth);
        assertValue(result, "SECTOR_REL_6M_XLK", expectedSixMonth);
        assertValue(result, "SECTOR_REL_12M_XLK", expectedTwelveMonth);
        assertValue(result, "SECTOR_RS_XLK", expectedCurrent);
        assertValue(result, "SECTOR_MOMENTUM_SCORE_XLK", 50);
        assertTrue(result.get("SECTOR_RS_XLK").formula().contains("ending one month ago"));
    }

    @Test
    void sectorRelativeStrengthPrefersDistributionAdjustedTotalReturnAndPublishesCoverage() {
        var priceBenchmark = points(300, ignored -> 100);
        var priceSector = points(300, index -> 100 + index);
        var totalBenchmark = points(300, ignored -> 100);
        var totalSector = points(300, index -> 200 - index * .25);
        var histories = Map.of(
                "YAHOO:SP500", priceBenchmark,
                "YAHOO:XLK", priceSector,
                "YAHOO:SPY_TR", totalBenchmark,
                "YAHOO:XLK_TR", totalSector
        );

        var result = policy.evaluate(Map.of(), histories, LocalDate.parse("2026-07-31"));

        assertTrue(result.get("SECTOR_REL_1M_XLK").value() < 0,
                "positive raw price history must not override a negative total-return history");
        assertValue(result, "SECTOR_TR_READY_XLK", 1);
        assertValue(result, "SECTOR_TOTAL_RETURN_COVERAGE", 6.25);
        assertTrue(result.get("SECTOR_RS_XLK").formula().contains("total-return"));
    }

    @Test
    void strategicThemeEtfsCannotDiluteTheStandardElevenSectorPercentile() {
        var benchmark = points(320, ignored -> 100);
        var standardLeader = points(320, index -> 100 + index * .45);
        var standardLaggard = points(320, index -> 100 + index * .10);
        var overlappingTheme = points(320, index -> 100 + index * .80);
        var standardOnly = policy.evaluate(Map.of(), Map.of(
                "YAHOO:SP500", benchmark,
                "YAHOO:XLK", standardLeader,
                "YAHOO:XLF", standardLaggard
        ), LocalDate.parse("2026-07-31"));
        var withTheme = policy.evaluate(Map.of(), Map.of(
                "YAHOO:SP500", benchmark,
                "YAHOO:XLK", standardLeader,
                "YAHOO:XLF", standardLaggard,
                "YAHOO:SOXX", overlappingTheme
        ), LocalDate.parse("2026-07-31"));

        assertValue(withTheme, "SECTOR_MOMENTUM_SCORE_XLK",
                standardOnly.get("SECTOR_MOMENTUM_SCORE_XLK").value());
        assertValue(withTheme, "SECTOR_MOMENTUM_SCORE_XLF",
                standardOnly.get("SECTOR_MOMENTUM_SCORE_XLF").value());
        // A one-member strategic universe is deliberately neutral rather than
        // changing either standard-sector percentile.
        assertValue(withTheme, "SECTOR_MOMENTUM_SCORE_SOXX", 50);
        assertTrue(withTheme.get("SECTOR_MOMENTUM_SCORE_XLK").formula()
                .contains("standard eleven-sector universe"));
        assertTrue(withTheme.get("SECTOR_MOMENTUM_SCORE_SOXX").formula()
                .contains("strategic-theme universe"));
    }

    private static List<MarketSeriesPoint> points(int count, Value value) {
        var points = new ArrayList<MarketSeriesPoint>(count);
        var start = LocalDate.parse("2026-01-01");
        for (var index = 0; index < count; index++) {
            points.add(new MarketSeriesPoint(start.plusDays(index), value.at(index)));
        }
        return List.copyOf(points);
    }

    private static void assertValue(Map<String, CoreDerivedIndicator> values, String key, double expected) {
        assertEquals(expected, values.get(key).value(), 0.0001, key);
    }

    @FunctionalInterface
    private interface Value {
        double at(int index);
    }
}
