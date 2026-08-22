package io.macrosquare.research.domain.narrative;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class NarrativeHeatPolicy {

    public NarrativeThemeState evaluate(NarrativeTheme theme, NarrativeEvidence evidence) {
        if (theme == null) throw new IllegalArgumentException("theme is required");
        if (evidence == null) throw new IllegalArgumentException("evidence is required");

        var context = new ScoringContext(evidence);
        switch (theme) {
            case AI_POWER -> scoreAiPower(context);
            case GRID_CAPEX -> scoreGridCapex(context);
            case DEFENSE_REARM -> scoreDefenseRearm(context);
            case FINANCE_LIQUIDITY -> scoreFinanceLiquidity(context);
            case ENERGY_SUPPLY -> scoreEnergySupply(context);
            case DIGITAL_ATTENTION -> scoreDigitalAttention(context);
            case CONSUMER_DEMAND -> scoreConsumerDemand(context);
            case CONSUMER_DEFENSIVE -> scoreConsumerDefensive(context);
            case MATERIALS_REFLATION -> scoreMaterialsReflation(context);
            case REAL_ASSETS_RATE -> scoreRealAssetsRate(context);
            case SAFEHAVEN_GOLD -> scoreSafehavenGold(context);
        }
        return context.finish(theme);
    }

    private static void scoreAiPower(ScoringContext context) {
        var soxx = context.derived("SECTOR_SOXX");
        var grid = context.derived("SECTOR_GRID");
        var igf = context.derived("SECTOR_IGF");
        var nasdaq = context.signal("NASDAQ");
        var disparity = context.derived("NASDAQ_DISPARITY");
        context.add("SECTOR_SOXX", "SOXX 30D", soxx == null ? 4 : soxx >= 12 ? 9 : soxx >= 5 ? 7 : soxx >= 0 ? 5 : 2,
                "SOXX " + fixedOneOrNa(soxx) + "%", soxx != null && soxx >= 5 ? "반도체 모멘텀 " + fixedOne(soxx) + "%" : null, null);
        context.add("SECTOR_GRID", "GRID 30D", grid == null ? 4 : grid >= 8 ? 8 : grid >= 3 ? 6 : grid >= 0 ? 5 : 3,
                "GRID " + fixedOneOrNa(grid) + "%", grid != null && grid >= 3 ? "전력망 프록시 " + fixedOne(grid) + "%" : null, null);
        context.add("SECTOR_IGF", "IGF 30D", igf == null ? 4 : igf >= 6 ? 7 : igf >= 2 ? 6 : igf >= 0 ? 5 : 3,
                "IGF " + fixedOneOrNa(igf) + "%", null, null);
        context.add("NASDAQ_SIGNAL", "NASDAQ 신호", signalScore(nasdaq), "NASDAQ " + signalOrNa(nasdaq),
                isBuy(nasdaq) ? "NASDAQ " + nasdaq : null, null);
        context.add("NASDAQ_DISPARITY", "NASDAQ 이격도", disparity == null ? 4 : disparity >= 15 ? 9 : disparity >= 8 ? 7 : disparity >= 0 ? 5 : 3,
                "이격 " + fixedOneOrNa(disparity) + "%", null,
                disparity != null && disparity >= 15 ? "NASDAQ 이격도 " + fixedOne(disparity) + "%" : null);
    }

    private static void scoreGridCapex(ScoringContext context) {
        var grid = context.derived("SECTOR_GRID");
        var igf = context.derived("SECTOR_IGF");
        var xlu = context.derived("SECTOR_XLU");
        var copper = context.signal("COPPER");
        var aiStrength = valueOr(context.evidence.manual().aiNarrativeStrength(), 0);
        context.add("SECTOR_GRID", "GRID 30D", grid == null ? 4 : grid >= 8 ? 9 : grid >= 3 ? 7 : grid >= 0 ? 5 : 2,
                "GRID " + fixedOneOrNa(grid) + "%", grid != null && grid >= 3 ? "전력망 수익률 " + fixedOne(grid) + "%" : null, null);
        context.add("SECTOR_IGF", "IGF 30D", igf == null ? 4 : igf >= 6 ? 8 : igf >= 2 ? 6 : igf >= 0 ? 5 : 3,
                "IGF " + fixedOneOrNa(igf) + "%", null, null);
        context.add("SECTOR_XLU", "XLU 30D", xlu == null ? 4 : xlu >= 4 ? 7 : xlu >= 0 ? 5 : 3,
                "XLU " + fixedOneOrNa(xlu) + "%", null, null);
        context.add("COPPER", "구리 신호", signalScore(copper), "COPPER " + signalOrNa(copper), null, null);
        context.add("AI_NARRATIVE_STRENGTH", "수동 AI 강도", aiStrength >= 2 ? 8 : aiStrength == 1 ? 6 : 4,
                "manual=" + aiStrength, aiStrength >= 1 ? "수동 AI 내러티브 " + aiStrength : null, null);
    }

    private static void scoreDefenseRearm(ScoringContext context) {
        var ita = context.derived("SECTOR_ITA");
        var geoRisk = valueOr(context.evidence.manual().geoRisk(), 0);
        var wti = context.raw("WTI");
        var gold = context.signal("GOLD");
        context.add("SECTOR_ITA", "ITA 30D", ita == null ? 4 : ita >= 8 ? 9 : ita >= 3 ? 7 : ita >= 0 ? 5 : 2,
                "ITA " + fixedOneOrNa(ita) + "%", ita != null && ita >= 3 ? "방산 프록시 " + fixedOne(ita) + "%" : null, null);
        context.add("GEO_RISK", "지정학 수동", geoRisk >= 4 ? 9 : geoRisk >= 3 ? 7 : geoRisk >= 2 ? 5 : 3,
                "geoRisk=" + geoRisk, geoRisk >= 3 ? "지정학 위험 " + geoRisk : null, null);
        context.add("WTI", "WTI 레벨", wti == null ? 4 : wti >= 85 ? 7 : wti >= 70 ? 5 : 4,
                "WTI " + fixedOneOrNa(wti), null, null);
        context.add("GOLD_SIGNAL", "금 신호", signalScore(gold), "GOLD " + signalOrNa(gold),
                isBuy(gold) ? "금 신호 " + gold : null, null);
    }

    private static void scoreFinanceLiquidity(ScoringContext context) {
        var xlf = context.derived("SECTOR_XLF");
        var vix = context.raw("VIXCLS");
        var nasdaq = context.signal("NASDAQ");
        context.add("SECTOR_XLF", "XLF 30D", xlf == null ? 4 : xlf >= 8 ? 8 : xlf >= 3 ? 6 : xlf >= 0 ? 5 : 3,
                "XLF " + fixedOneOrNa(xlf) + "%", xlf != null && xlf >= 3 ? "금융 모멘텀 " + fixedOne(xlf) + "%" : null, null);
        context.add("VIXCLS", "VIX", vix == null ? 4 : vix >= 28 ? 3 : vix >= 20 ? 5 : 7,
                "VIX " + fixedOneOrNa(vix), null, vix != null && vix >= 28 ? "변동성 " + fixedOne(vix) : null);
        context.add("NASDAQ_SIGNAL", "NASDAQ 신호", signalScore(nasdaq), "NASDAQ " + signalOrNa(nasdaq), null, null);
    }

    private static void scoreEnergySupply(ScoringContext context) {
        var xle = context.derived("SECTOR_XLE");
        var wti = context.raw("WTI");
        var copper = context.signal("COPPER");
        context.add("SECTOR_XLE", "XLE 30D", xle == null ? 4 : xle >= 10 ? 9 : xle >= 4 ? 7 : xle >= 0 ? 5 : 2,
                "XLE " + fixedOneOrNa(xle) + "%", xle != null && xle >= 4 ? "에너지 모멘텀 " + fixedOne(xle) + "%" : null, null);
        context.add("WTI", "WTI", wti == null ? 4 : wti >= 85 ? 8 : wti >= 72 ? 6 : wti >= 60 ? 5 : 3,
                "WTI " + fixedOneOrNa(wti), null, wti != null && wti >= 85 ? "유가 " + fixedOne(wti) : null);
        context.add("COPPER", "구리 신호", signalScore(copper), "COPPER " + signalOrNa(copper), null, null);
    }

    private static void scoreDigitalAttention(ScoringContext context) {
        var xlc = context.derived("SECTOR_XLC");
        var nasdaq = context.signal("NASDAQ");
        var aiStrength = valueOr(context.evidence.manual().aiNarrativeStrength(), 0);
        context.add("SECTOR_XLC", "XLC 30D", xlc == null ? 4 : xlc >= 8 ? 8 : xlc >= 3 ? 6 : xlc >= 0 ? 5 : 3,
                "XLC " + fixedOneOrNa(xlc) + "%", xlc != null && xlc >= 3 ? "커뮤니케이션 모멘텀 " + fixedOne(xlc) + "%" : null, null);
        context.add("NASDAQ_SIGNAL", "NASDAQ 신호", signalScore(nasdaq), "NASDAQ " + signalOrNa(nasdaq), null, null);
        context.add("AI_NARRATIVE_STRENGTH", "AI/광고 확산", aiStrength >= 2 ? 7 : aiStrength == 1 ? 6 : 4,
                "manual=" + aiStrength, null, null);
    }

    private static void scoreConsumerDemand(ScoringContext context) {
        var xly = context.derived("SECTOR_XLY");
        var xlp = context.derived("SECTOR_XLP");
        var copper = context.signal("COPPER");
        context.add("SECTOR_XLY", "XLY 30D", xly == null ? 4 : xly >= 8 ? 8 : xly >= 3 ? 6 : xly >= 0 ? 5 : 3,
                "XLY " + fixedOneOrNa(xly) + "%", xly != null && xly >= 3 ? "소비 수요 " + fixedOne(xly) + "%" : null, null);
        context.add("SECTOR_XLP", "XLP 30D", xlp == null ? 4 : xlp >= 4 ? 6 : xlp >= 0 ? 5 : 3,
                "XLP " + fixedOneOrNa(xlp) + "%", null, null);
        context.add("COPPER", "구리 신호", signalScore(copper), "COPPER " + signalOrNa(copper), null, null);
    }

    private static void scoreConsumerDefensive(ScoringContext context) {
        var xly = context.derived("SECTOR_XLY");
        var xlp = context.derived("SECTOR_XLP");
        var xlv = context.derived("SECTOR_XLV");
        context.add("SECTOR_XLY", "XLY 30D", xly == null ? 4 : xly >= 8 ? 8 : xly >= 3 ? 6 : xly >= 0 ? 5 : 3,
                "XLY " + fixedOneOrNa(xly) + "%", null, null);
        // Keep the current Node detail strings byte-for-byte compatible (XLP/XLV omit '%').
        context.add("SECTOR_XLP", "XLP 30D", xlp == null ? 4 : xlp >= 5 ? 7 : xlp >= 1 ? 6 : xlp >= 0 ? 5 : 3,
                "XLP " + fixedOneOrNa(xlp), null, null);
        context.add("SECTOR_XLV", "XLV 30D", xlv == null ? 4 : xlv >= 5 ? 7 : xlv >= 1 ? 6 : xlv >= 0 ? 5 : 3,
                "XLV " + fixedOneOrNa(xlv), null, null);
    }

    private static void scoreMaterialsReflation(ScoringContext context) {
        var xlb = context.derived("SECTOR_XLB");
        var wti = context.raw("WTI");
        var copper = context.signal("COPPER");
        context.add("SECTOR_XLB", "XLB 30D", xlb == null ? 4 : xlb >= 8 ? 8 : xlb >= 3 ? 6 : xlb >= 0 ? 5 : 3,
                "XLB " + fixedOneOrNa(xlb), xlb != null && xlb >= 3 ? "소재 모멘텀 " + fixedOne(xlb) + "%" : null, null);
        context.add("WTI", "WTI", wti == null ? 4 : wti >= 82 ? 7 : wti >= 70 ? 6 : 4,
                "WTI " + fixedOneOrNa(wti), null, null);
        context.add("COPPER", "구리 신호", signalScore(copper), "COPPER " + signalOrNa(copper),
                isBuy(copper) ? "구리 " + copper : null, null);
    }

    private static void scoreRealAssetsRate(ScoringContext context) {
        var xlre = context.derived("SECTOR_XLRE");
        var igf = context.derived("SECTOR_IGF");
        var gold = context.signal("GOLD");
        context.add("SECTOR_XLRE", "XLRE 30D", xlre == null ? 4 : xlre >= 6 ? 7 : xlre >= 1 ? 6 : xlre >= 0 ? 5 : 3,
                "XLRE " + fixedOneOrNa(xlre) + "%", null, null);
        context.add("SECTOR_IGF", "IGF 30D", igf == null ? 4 : igf >= 6 ? 7 : igf >= 2 ? 6 : igf >= 0 ? 5 : 3,
                "IGF " + fixedOneOrNa(igf), null, null);
        context.add("GOLD_SIGNAL", "금 신호", signalScore(gold), "GOLD " + signalOrNa(gold), null, null);
    }

    private static void scoreSafehavenGold(ScoringContext context) {
        var gold = context.signal("GOLD");
        var priority = context.derived("GOLD_PRIORITY_SCORE");
        var vix = context.raw("VIXCLS");
        var disparity = context.derived("GOLD_DISPARITY");
        var centralBankDemand = context.derived("CB_GOLD_STRUCTURAL_DEMAND");
        context.add("GOLD_SIGNAL", "금 신호", signalScore(gold), "GOLD " + signalOrNa(gold),
                isBuy(gold) ? "금 신호 " + gold : null, null);
        context.add("GOLD_PRIORITY_SCORE", "금 우선순위", priority == null ? 4 : priority >= 0.7 ? 9 : priority >= 0.4 ? 7 : priority >= 0.2 ? 5 : 3,
                "score " + fixedTwoOrNa(priority), null, null);
        context.add("VIXCLS", "VIX", vix == null ? 4 : vix >= 30 ? 9 : vix >= 22 ? 7 : vix >= 15 ? 5 : 3,
                "VIX " + fixedOneOrNa(vix), vix != null && vix >= 30 ? "VIX " + fixedOne(vix) : null, null);
        context.add("GOLD_DISPARITY", "금 이격도", disparity == null ? 4 : disparity >= 18 ? 9 : disparity >= 10 ? 7 : disparity >= 0 ? 5 : 3,
                "이격 " + fixedOneOrNa(disparity) + "%", null,
                disparity != null && disparity >= 18 ? "금 이격도 " + fixedOne(disparity) + "%" : null);
        context.add("CB_GOLD_STRUCTURAL_DEMAND", "중앙은행 수요", centralBankDemand == null ? 4 : centralBankDemand >= 0.7 ? 8 : centralBankDemand >= 0.4 ? 6 : centralBankDemand >= 0.2 ? 5 : 3,
                "CB demand " + fixedTwoOrNa(centralBankDemand), null, null);
    }

    private static boolean isBuy(AssetSignalAction signal) {
        return signal == AssetSignalAction.BUY || signal == AssetSignalAction.STRONG_BUY;
    }

    private static double signalScore(AssetSignalAction signal) {
        if (signal == null) return 0;
        return switch (signal) {
            case STRONG_BUY -> 9;
            case BUY -> 7;
            case HOLD -> 5;
            case REDUCE -> 3;
            case SELL -> 1;
        };
    }

    private static String signalOrNa(AssetSignalAction signal) {
        return signal == null ? "n/a" : signal.name();
    }

    private static int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String fixedOne(double value) {
        return fixed(value, 1);
    }

    private static String fixedOneOrNa(Double value) {
        return value == null ? "n/a" : fixedOne(value);
    }

    private static String fixedTwoOrNa(Double value) {
        return value == null ? "n/a" : fixed(value, 2);
    }

    private static String fixed(double value, int scale) {
        // JavaScript Number#toFixed rounds the exact IEEE-754 value. BigDecimal(double)
        // deliberately preserves that value, unlike String.format/BigDecimal.valueOf.
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    private static String compact(Double value) {
        return value == null ? "n/a" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static NarrativeStage stageFromHeat(int heatScore) {
        if (heatScore >= 66) return NarrativeStage.OVERHEATED;
        if (heatScore >= 36) return NarrativeStage.MID;
        return NarrativeStage.EARLY;
    }

    private static final class ScoringContext {
        private final NarrativeEvidence evidence;
        private final List<NarrativeProxyScore> proxyScores = new ArrayList<>();
        private final List<String> drivers = new ArrayList<>();
        private final List<String> risks = new ArrayList<>();

        private ScoringContext(NarrativeEvidence evidence) {
            this.evidence = evidence;
        }

        private Double raw(String key) {
            return evidence.rawValues().get(key);
        }

        private Double derived(String key) {
            return evidence.derivedValues().get(key);
        }

        private AssetSignalAction signal(String asset) {
            return evidence.assetSignals().get(asset);
        }

        private void add(
                String key,
                String label,
                double score,
                String detail,
                String driver,
                String risk
        ) {
            proxyScores.add(new NarrativeProxyScore(key, label, clamp(score, 0, 10), detail));
            if (driver != null) drivers.add(driver);
            if (risk != null) risks.add(risk);
        }

        private NarrativeThemeState finish(NarrativeTheme theme) {
            var externalDrivers = evidence.externalSignals().stream()
                    .filter(signal -> signal.weight() > 0 && signal.score() >= 7)
                    .map(signal -> signal.label() + " " + compact(signal.value()))
                    .toList();
            var externalRisks = evidence.externalSignals().stream()
                    .filter(signal -> signal.weight() > 0 && signal.score() >= 8.5)
                    .map(signal -> signal.label() + " 과열 " + compact(signal.value()))
                    .toList();
            var total = proxyScores.stream().mapToDouble(NarrativeProxyScore::score).sum()
                    + evidence.externalSignals().stream().mapToDouble(
                            signal -> signal.score() * signal.weight()).sum();
            var count = proxyScores.size()
                    + evidence.externalSignals().stream().mapToDouble(NarrativeExternalSignal::weight).sum();
            var heatScore = count == 0 ? 0 : (int) Math.round((total / count) * 10);
            var combinedDrivers = new ArrayList<>(drivers);
            combinedDrivers.addAll(externalDrivers);
            var combinedRisks = new ArrayList<>(risks);
            combinedRisks.addAll(externalRisks);
            return new NarrativeThemeState(
                    theme,
                    stageFromHeat(heatScore),
                    heatScore,
                    combinedDrivers.stream().limit(6).toList(),
                    combinedRisks.stream().limit(4).toList(),
                    proxyScores,
                    evidence.externalSignals()
            );
        }
    }
}
