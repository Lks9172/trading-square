package io.macrosquare.market.domain.signal;

import io.macrosquare.market.domain.regime.MacroRegime;
import io.macrosquare.market.domain.regime.MacroRegimeAssessment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAssetSignalPolicyTest {

    private static final LocalDate DATE = LocalDate.parse("2026-08-05");
    private final CoreAssetSignalPolicy policy = new CoreAssetSignalPolicy();

    @Test
    void missingInputsAreNotCountedAsFriendlyAndNeutralizeTheAction() {
        var signal = signal("NASDAQ", Map.of(), Map.of(), regime(MacroRegime.RISK_ON, 70));

        assertEquals(CoreSignalAction.HOLD, signal.action());
        assertEquals(1, signal.conditionsAvailable());
        assertEquals(7, signal.conditionsTotal());
        assertEquals(15, signal.dataCoveragePct());
        assertFalse(signal.missingReasons().isEmpty());
        assertTrue(signal.unmetReasons().stream().anyMatch(value -> value.contains("중립화")));
    }

    @Test
    void liquidityDrainExtensionAndTailRiskPreventAggressiveNasdaqAndLeverageSignals() {
        var raw = Map.of("VIXCLS", 15.86, "DXY", 99.7, "WTI", 76.0, "USDKRW", 1422.0);
        var derived = new LinkedHashMap<String, Double>();
        derived.put("CREDIT_STRESS_FLAG", 0d);
        derived.put("NASDAQ_DISPARITY", 10.6);
        derived.put("NASDAQ_ABOVE_200DMA", 1d);
        derived.put("NASDAQ_RSI_14", 53d);
        derived.put("GLOBAL_M2_PROXY", 5.5);
        derived.put("OVERHEATED", 0d);
        derived.put("LIQUIDITY_PLUMBING_SIGNAL", -2d);
        derived.put("LIQUIDITY_DIRECTION", -1d);
        derived.put("NET_LIQUIDITY_IMPULSE_4W_BN", -50d);
        derived.put("LIQUIDITY_TRANSMISSION_STRESS_SCORE", 2d);
        derived.put("LIQUIDITY_TRANSMISSION_COVERAGE", 100d);
        derived.put("TAIL_RISK_LEVEL", 2d);
        derived.put("NASDAQ_STRUCTURE_SCORE", 60d);
        derived.put("NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0d);

        var values = policy.evaluate(raw, derived, regime(MacroRegime.BOND_VIGILANTE, 69), DATE);
        var nasdaq = find(values, "NASDAQ");
        var leverage = find(values, "LEVERAGE");

        assertEquals(90, nasdaq.weightedScore());
        assertEquals(CoreSignalAction.BUY, nasdaq.action());
        assertTrue(nasdaq.unmetReasons().stream().anyMatch(value -> value.contains("순유동성")));
        assertEquals(CoreSignalAction.HOLD, leverage.action());
        assertEquals(null, leverage.leverageTier());
        assertTrue(leverage.unmetReasons().stream().anyMatch(value -> value.contains("꼬리위험")));
    }

    @Test
    void insufficientTransmissionCoverageCannotLookLikeAZeroStressGreenLight() {
        var raw = Map.of("VIXCLS", 18d);
        var derived = new LinkedHashMap<String, Double>();
        derived.put("CREDIT_STRESS_FLAG", 0d);
        derived.put("NASDAQ_DISPARITY", 1d);
        derived.put("NASDAQ_ABOVE_200DMA", 1d);
        derived.put("NASDAQ_RSI_14", 55d);
        derived.put("OVERHEATED", 0d);
        derived.put("LIQUIDITY_PLUMBING_SIGNAL", 2d);
        derived.put("LIQUIDITY_DIRECTION", 2d);
        derived.put("NET_LIQUIDITY_IMPULSE_4W_BN", 120d);
        derived.put("LIQUIDITY_TRANSMISSION_STRESS_SCORE", 0d);
        derived.put("LIQUIDITY_TRANSMISSION_COVERAGE", 33d);
        derived.put("NASDAQ_STRUCTURE_SCORE", 80d);
        derived.put("NASDAQ_FIB_LAST_DEFENSE_BROKEN", 0d);
        derived.put("TAIL_RISK_LEVEL", 0d);

        var values = policy.evaluate(raw, derived, regime(MacroRegime.RISK_ON, 80), DATE);

        assertEquals(CoreSignalAction.BUY, find(values, "NASDAQ").action());
        assertEquals(CoreSignalAction.HOLD, find(values, "LEVERAGE").action());
        assertTrue(find(values, "NASDAQ").unmetReasons().stream().anyMatch(value -> value.contains("2/3 미만")));
    }

    @Test
    void brokenKospiStructureOverridesAHighMacroConditionScore() {
        var raw = Map.of("DXY", 99.7);
        var derived = new LinkedHashMap<String, Double>();
        derived.put("KRW_FX_LEVEL", 0d);
        derived.put("KOSPI_ABOVE_200DMA", 1d);
        derived.put("KOSPI_DISPARITY", 14.5);
        derived.put("SECTOR_SOXX", -6.7);
        derived.put("CREDIT_STRESS_FLAG", 0d);
        derived.put("KOSPI_OVERHEATED", 0d);
        derived.put("KOSPI_FOREIGN_NET_20D", 8_711d);
        derived.put("KOSPI_FOREIGN_TREND", 17_610d);
        derived.put("KOSPI_FOREIGN_SELL_STREAK", 0d);
        derived.put("KOSPI_STRUCTURE_SCORE", 16d);
        derived.put("KOSPI_FIB_LAST_DEFENSE_BROKEN", 1d);

        var signal = signal("KOSPI", raw, derived, regime(MacroRegime.BOND_VIGILANTE, 69));

        assertTrue(signal.weightedScore() >= 80);
        assertEquals(CoreSignalAction.HOLD, signal.action());
        assertTrue(signal.unmetReasons().stream().anyMatch(value -> value.contains("가격 구조 훼손")));
    }

    @Test
    void goldDownSwingBelowItsLongTrendCannotBecomeAnImmediateBuy() {
        var raw = Map.of("DXY", 99.7);
        var derived = Map.of(
                "REAL_YIELD", 2.47,
                "GOLD_ABOVE_200DMA", 0d,
                "GOLD_RSI_14", 66d,
                "GOLD_SILVER_RATIO", 68d,
                "CREDIT_STRESS_FLAG", 0d,
                "GOLD_FIB_SWING_DIRECTION", -1d
        );

        var signal = signal("GOLD", raw, derived, regime(MacroRegime.BOND_VIGILANTE, 69));

        assertEquals(CoreSignalAction.HOLD, signal.action());
        assertTrue(signal.unmetReasons().stream().anyMatch(value -> value.contains("하락 주요 파동")));
    }

    @Test
    void silverMacroAndRelativeValueAloneCannotCreateAStrongBuy() {
        var raw = Map.of("DXY", 99d);
        var derived = Map.of(
                "GOLD_SILVER_RATIO", 90d,
                "COPPER_GOLD_RATIO_TREND", 1d,
                "CREDIT_STRESS_FLAG", 0d,
                "GLOBAL_M2_PROXY", 5d,
                "STAGFLATION_WARNING", 0d
        );

        var signal = signal("SILVER", raw, derived, regime(MacroRegime.RISK_ON, 80));

        assertEquals(100, signal.weightedScore());
        assertEquals(CoreSignalAction.BUY, signal.action());
        assertTrue(signal.unmetReasons().stream().anyMatch(value -> value.contains("은 자체 가격")));
    }

    @Test
    void bondVigilanteRegimeCannotDescribeCashAsAnOutrightSell() {
        var raw = Map.of("VIXCLS", 16d);
        var derived = Map.of(
                "CREDIT_STRESS_FLAG", 0d,
                "OVERHEATED", 0d,
                "FISCAL_STRESS", 0d,
                "STAGFLATION_WARNING", 0d,
                "BOND_VIGILANTE_WARNING", 1d,
                "LIQUIDITY_DIRECTION", -1d
        );

        var signal = signal("CASH", raw, derived, regime(MacroRegime.BOND_VIGILANTE, 65));

        assertEquals(50, signal.weightedScore());
        assertEquals(CoreSignalAction.HOLD, signal.action());
        assertTrue(signal.reasons().stream().anyMatch(value -> value.contains("장기금리 구조 위험")));
    }

    private CoreAssetSignal signal(
            String asset,
            Map<String, Double> raw,
            Map<String, Double> derived,
            MacroRegimeAssessment regime
    ) {
        return find(policy.evaluate(raw, derived, regime, DATE), asset);
    }

    private static CoreAssetSignal find(List<CoreAssetSignal> values, String asset) {
        return values.stream().filter(value -> value.asset().equals(asset)).findFirst().orElseThrow();
    }

    private static MacroRegimeAssessment regime(MacroRegime regime, int score) {
        return new MacroRegimeAssessment(regime, score, Map.of(), DATE, List.of());
    }
}
