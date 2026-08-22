package io.macrosquare.market.domain.regime;

import io.macrosquare.market.domain.regime.MacroRegime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact core scoring and override order used by the production macro regime. */
public final class MacroRegimePolicy {

    private static final Map<String, Double> WEIGHTS = Map.ofEntries(
            Map.entry("vix", 1.5), Map.entry("yieldCurve", 1.0), Map.entry("hySpread", 1.2),
            Map.entry("joblessClaims", 1.5), Map.entry("nasdaqDisparity", 1.0),
            Map.entry("finStress", 1.0), Map.entry("dxy", .8), Map.entry("liquidityDir", 1.0),
            Map.entry("wti", .6), Map.entry("globalM2", .7), Map.entry("smartMoney", .6),
            Map.entry("sectorMomentum", .8), Map.entry("policy", .5), Map.entry("geoRisk", .5),
            Map.entry("dgs10Level", .5), Map.entry("unrateLevel", .5)
    );

    public MacroRegimeAssessment evaluate(MacroRegimeEvidence evidence, LocalDate asOf) {
        var raw = evidence.raw();
        var derived = evidence.derived();
        var components = new LinkedHashMap<String, Integer>();
        components.put("vix", scoreVix(raw.get("VIXCLS")));
        components.put("yieldCurve", scoreYieldCurve(raw.get("T10Y2Y")));
        components.put("hySpread", scoreHighYield(raw.get("BAMLH0A0HYM2")));
        components.put("joblessClaims", scoreClaims(raw.get("ICSA")));
        components.put("nasdaqDisparity", scoreDisparity(derived.get("NASDAQ_DISPARITY")));
        components.put("finStress", scoreFinancialStress(raw.get("STLFSI4")));
        components.put("dxy", scoreDollar(raw.get("DXY")));
        components.put("liquidityDir", scoreLiquidity(derived));
        components.put("wti", scoreOil(raw.get("WTI")));
        components.put("globalM2", scoreGlobalM2(derived.get("GLOBAL_M2_PROXY")));
        components.put("smartMoney", (int) Math.round(evidence.smartMoneyScore()));
        components.put("sectorMomentum", scoreSectors(derived));
        components.put("policy", evidence.policyDirection());
        components.put("geoRisk", clampInt(2 - evidence.geopoliticalRisk(), -2, 2));
        components.put("dgs10Level", scoreTenYear(raw.get("DGS10")));
        components.put("unrateLevel", scoreUnemployment(raw.get("UNRATE")));

        double weighted = 0;
        double totalWeight = 0;
        for (var entry : components.entrySet()) {
            var weight = WEIGHTS.getOrDefault(entry.getKey(), 1d);
            weighted += entry.getValue() * weight;
            totalWeight += weight;
        }
        var normalized = ((weighted / totalWeight + 2) / 4) * 100;
        var score = clampInt((int) Math.round(normalized), 0, 100);
        if (lessOrEqual(derived.get("DRAWDOWN_TYPE_CLASSIFIER"), -2)) score = Math.max(0, score - 5);
        if (equal(derived.get("REGIME_SECTOR_LEADERSHIP_MATCH"), 1)) score = Math.min(100, score + 2);
        if (equal(derived.get("REGIME_SECTOR_LEADERSHIP_MATCH"), -1)) score = Math.max(0, score - 2);

        var regime = score >= 75 ? MacroRegime.RISK_ON
                : score >= 55 ? MacroRegime.NEUTRAL
                : score >= 40 ? MacroRegime.CAUTION
                : score >= 25 ? MacroRegime.CORRECTION
                : raw.get("ICSA") != null && raw.get("ICSA") < 300_000
                ? MacroRegime.PANIC_BUT_OK : MacroRegime.RECESSION_RISK;
        var overrides = new ArrayList<String>();

        var stagflation = equal(derived.get("STAGFLATION_WARNING"), 1);
        var vigilante = equal(derived.get("BOND_VIGILANTE_WARNING"), 1);
        if (stagflation && vigilante) {
            regime = MacroRegime.STAGFLATION_BOND_VIGILANTE;
            overrides.add("STAGFLATION_WARNING+BOND_VIGILANTE_WARNING");
        } else if (stagflation) {
            regime = MacroRegime.STAGFLATION;
            overrides.add("STAGFLATION_WARNING");
        } else if (vigilante) {
            regime = MacroRegime.BOND_VIGILANTE;
            overrides.add("BOND_VIGILANTE_WARNING");
        } else if (equal(derived.get("OVERHEATED"), 1) && score >= 55) {
            regime = MacroRegime.CAUTION;
            overrides.add("OVERHEATED");
        }
        if (equal(derived.get("GOLDILOCKS_ZONE"), -1)
                && (regime == MacroRegime.RISK_ON || regime == MacroRegime.NEUTRAL)) {
            regime = MacroRegime.CAUTION;
            overrides.add("GOLDILOCKS_ZONE=-1");
        }
        if (lessOrEqual(derived.get("DRAWDOWN_TYPE_CLASSIFIER"), -2)
                && regime != MacroRegime.STAGFLATION
                && regime != MacroRegime.BOND_VIGILANTE
                && regime != MacroRegime.STAGFLATION_BOND_VIGILANTE) {
            regime = MacroRegime.RECESSION_RISK;
            overrides.add("DRAWDOWN_TYPE_CLASSIFIER=SYSTEMIC_RISK");
        }
        if (equal(derived.get("CREDIT_STRESS_FLAG"), 1)) {
            if (regime == MacroRegime.RISK_ON) {
                regime = MacroRegime.NEUTRAL;
                overrides.add("CREDIT_STRESS_FLAG");
            } else if (regime == MacroRegime.NEUTRAL) {
                regime = MacroRegime.CAUTION;
                overrides.add("CREDIT_STRESS_FLAG");
            }
        }
        return new MacroRegimeAssessment(regime, score, components, asOf, overrides);
    }

    private static int scoreVix(Double value) {
        if (value == null) return 0;
        return value > 40 ? -2 : value > 30 ? -1 : value > 20 ? 0 : value > 15 ? 1 : 2;
    }

    private static int scoreYieldCurve(Double value) {
        if (value == null) return 0;
        return value < -.5 ? -2 : value < 0 ? -1 : value < .5 ? 0 : value < 1.5 ? 1 : 2;
    }

    private static int scoreHighYield(Double value) {
        if (value == null) return 0;
        return value > 8 ? -2 : value > 6 ? -1 : value > 4 ? 0 : value > 3 ? 1 : 2;
    }

    private static int scoreClaims(Double value) {
        if (value == null) return 0;
        return value > 350_000 ? -2 : value > 300_000 ? -1 : value > 250_000 ? 0 : value > 200_000 ? 1 : 2;
    }

    private static int scoreDisparity(Double value) {
        if (value == null) return 0;
        return value < -25 ? -2
                : value < -10 ? -1
                : value < 0 ? 0
                : value <= 8 ? 1
                : value <= 12 ? 0
                : value <= 20 ? -1 : -2;
    }

    private static int scoreFinancialStress(Double value) {
        if (value == null) return 0;
        return value > 3 ? -2 : value > 1 ? -1 : value > 0 ? 0 : value > -.5 ? 1 : 2;
    }

    private static int scoreDollar(Double value) {
        if (value == null) return 0;
        return value > 108 ? -2 : value > 104 ? -1 : value > 100 ? 0 : value > 96 ? 1 : 2;
    }

    private static int scoreOil(Double value) {
        if (value == null) return 0;
        return value > 100 ? -2
                : value > 85 ? -1
                : value >= 65 ? 0
                : value >= 50 ? 1
                : value >= 40 ? 0 : -1;
    }

    private static int scoreGlobalM2(Double value) {
        if (value == null) return 0;
        return value > 3 ? 2 : value > 1 ? 1 : value > -1 ? 0 : value > -3 ? -1 : -2;
    }

    private static int scoreTenYear(Double value) {
        if (value == null) return 0;
        return value >= 5 ? -2 : value >= 4 ? -1 : value >= 3 ? 0 : 1;
    }

    private static int scoreUnemployment(Double value) {
        if (value == null) return 0;
        return value < 4 ? 2 : value < 5 ? 1 : value < 6 ? -1 : -2;
    }

    private static int scoreLiquidity(Map<String, Double> derived) {
        var direct = derived.get("LIQUIDITY_DIRECTION");
        if (direct != null) return direct >= 2 ? 2 : direct >= 1 ? 1 : direct <= -2 ? -2 : direct <= -1 ? -1 : 0;
        return 0;
    }

    private static int scoreSectors(Map<String, Double> derived) {
        var cyclical = average(derived, "XLK", "XLI", "XLY", "XLC", "XLB", "XLF");
        var defensive = average(derived, "XLV", "XLU", "XLP");
        if (cyclical == null || defensive == null) return 0;
        var spread = cyclical - defensive;
        return spread >= 3 ? 2 : spread >= 1 ? 1 : spread <= -3 ? -2 : spread <= -1 ? -1 : 0;
    }

    private static Double average(Map<String, Double> derived, String... sectorKeys) {
        double sum = 0;
        int count = 0;
        for (var key : sectorKeys) {
            var value = derived.get("SECTOR_" + key);
            if (value == null) continue;
            sum += value;
            count++;
        }
        return count == 0 ? null : sum / count;
    }

    private static boolean equal(Double value, double expected) {
        return value != null && Double.compare(value, expected) == 0;
    }

    private static boolean lessOrEqual(Double value, double expected) {
        return value != null && value <= expected;
    }

    private static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
