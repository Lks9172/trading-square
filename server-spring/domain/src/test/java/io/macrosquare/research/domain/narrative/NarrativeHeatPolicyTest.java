package io.macrosquare.research.domain.narrative;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NarrativeHeatPolicyTest {

    private final NarrativeHeatPolicy policy = new NarrativeHeatPolicy();

    @Test
    void matchesAllExistingTypeScriptThemeGoldenMasters() {
        var evidence = goldenEvidence();
        var states = java.util.Arrays.stream(NarrativeTheme.values())
                .map(theme -> policy.evaluate(theme, evidence))
                .toList();

        var summaries = states.stream().collect(Collectors.toMap(
                state -> state.theme().id(),
                state -> state.stage() + ":" + state.heatScore(),
                (left, right) -> left,
                LinkedHashMap::new
        ));
        assertEquals(Map.ofEntries(
                Map.entry("ai-power", "OVERHEATED:85"),
                Map.entry("grid-capex", "OVERHEATED:80"),
                Map.entry("defense-rearm", "OVERHEATED:76"),
                Map.entry("finance-liquidity", "OVERHEATED:68"),
                Map.entry("energy-supply", "OVERHEATED:70"),
                Map.entry("digital-attention", "OVERHEATED:78"),
                Map.entry("consumer-demand", "OVERHEATED:68"),
                Map.entry("consumer-defensive", "OVERHEATED:68"),
                Map.entry("materials-reflation", "MID:65"),
                Map.entry("real-assets-rate", "OVERHEATED:73"),
                Map.entry("safehaven-gold", "OVERHEATED:85")
        ), summaries);
    }

    @Test
    void preservesAiDriversRisksAndProxyDetailsExactly() {
        var result = policy.evaluate(NarrativeTheme.AI_POWER, goldenEvidence());

        assertEquals(List.of(
                "반도체 모멘텀 14.0%",
                "전력망 프록시 9.0%",
                "NASDAQ STRONG_BUY",
                "YouTube 30D 600"
        ), result.drivers());
        assertEquals(List.of(
                "NASDAQ 이격도 16.0%",
                "YouTube 30D 과열 600"
        ), result.risks());
        assertEquals(List.of(
                new NarrativeProxyScore("SECTOR_SOXX", "SOXX 30D", 9, "SOXX 14.0%"),
                new NarrativeProxyScore("SECTOR_GRID", "GRID 30D", 8, "GRID 9.0%"),
                new NarrativeProxyScore("SECTOR_IGF", "IGF 30D", 7, "IGF 7.0%"),
                new NarrativeProxyScore("NASDAQ_SIGNAL", "NASDAQ 신호", 9, "NASDAQ STRONG_BUY"),
                new NarrativeProxyScore("NASDAQ_DISPARITY", "NASDAQ 이격도", 9, "이격 16.0%")
        ), result.proxyScores());
        assertEquals(goldenEvidence().externalSignals(), result.externalSignals());
    }

    @Test
    void preservesGoldHeatAndRiskDetailsExactly() {
        var result = policy.evaluate(NarrativeTheme.SAFEHAVEN_GOLD, goldenEvidence());

        assertEquals(85, result.heatScore());
        assertEquals(List.of("금 신호 BUY", "VIX 31.0", "YouTube 30D 600"), result.drivers());
        assertEquals(List.of("금 이격도 19.0%", "YouTube 30D 과열 600"), result.risks());
        assertEquals("score 0.80", result.proxyScores().get(1).detail());
        assertEquals("CB demand 0.75", result.proxyScores().get(4).detail());
    }

    @Test
    void preservesCurrentNodeConsumerProxyDetailsExactly() {
        var demand = policy.evaluate(NarrativeTheme.CONSUMER_DEMAND, goldenEvidence());
        var defensive = policy.evaluate(NarrativeTheme.CONSUMER_DEFENSIVE, goldenEvidence());

        assertEquals(List.of("XLY 3.0%", "XLP 2.0%", "COPPER BUY"),
                demand.proxyScores().stream().map(NarrativeProxyScore::detail).toList());
        assertEquals(List.of("XLY 3.0%", "XLP 2.0", "XLV 2.0"),
                defensive.proxyScores().stream().map(NarrativeProxyScore::detail).toList());
    }

    @Test
    void roundsProxyDetailsLikeJavaScriptToFixedForLiveNegativeValues() {
        var evidence = new NarrativeEvidence(
                Map.of(),
                Map.of("SECTOR_XLY", -2.55, "SECTOR_XLP", -0.47, "SECTOR_XLV", 5.33),
                Map.of("COPPER", AssetSignalAction.STRONG_BUY),
                null,
                List.of()
        );

        var demand = policy.evaluate(NarrativeTheme.CONSUMER_DEMAND, evidence);
        var defensive = policy.evaluate(NarrativeTheme.CONSUMER_DEFENSIVE, evidence);

        assertEquals(List.of("XLY -2.5%", "XLP -0.5%", "COPPER STRONG_BUY"),
                demand.proxyScores().stream().map(NarrativeProxyScore::detail).toList());
        assertEquals(List.of("XLY -2.5%", "XLP -0.5", "XLV 5.3"),
                defensive.proxyScores().stream().map(NarrativeProxyScore::detail).toList());
    }

    @Test
    void treatsMissingEvidenceLikeTheCurrentNodePolicy() {
        var result = policy.evaluate(
                NarrativeTheme.AI_POWER,
                new NarrativeEvidence(Map.of(), Map.of(), Map.of(), null, List.of())
        );

        assertEquals(NarrativeStage.EARLY, result.stage());
        assertEquals(32, result.heatScore());
        assertEquals(List.of(), result.drivers());
        assertEquals(List.of(), result.risks());
        assertEquals("NASDAQ n/a", result.proxyScores().get(3).detail());
    }

    private static NarrativeEvidence goldenEvidence() {
        var raw = new LinkedHashMap<String, Double>();
        raw.put("DXY", 100.0);
        raw.put("WTI", 68.0);
        raw.put("T10Y2Y", 0.6);
        raw.put("STLFSI4", -0.2);
        raw.put("BAMLH0A0HYM2", 3.4);
        raw.put("VIXCLS", 31.0);

        var derived = new LinkedHashMap<String, Double>();
        derived.put("SECTOR_SOXX", 14.0);
        derived.put("SECTOR_GRID", 9.0);
        derived.put("SECTOR_IGF", 7.0);
        derived.put("SECTOR_ITA", 10.0);
        derived.put("NASDAQ_DISPARITY", 16.0);
        derived.put("GOLD_PRIORITY_SCORE", 0.8);
        derived.put("GOLD_DISPARITY", 19.0);
        derived.put("CB_GOLD_STRUCTURAL_DEMAND", 0.75);
        derived.put("SECTOR_XLU", 4.0);
        derived.put("SECTOR_XLF", 5.0);
        derived.put("SECTOR_XLE", 6.0);
        derived.put("SECTOR_XLC", 4.0);
        derived.put("SECTOR_XLY", 3.0);
        derived.put("SECTOR_XLP", 2.0);
        derived.put("SECTOR_XLV", 2.0);
        derived.put("SECTOR_XLB", 5.0);
        derived.put("SECTOR_XLRE", 2.0);

        return new NarrativeEvidence(
                raw,
                derived,
                Map.of(
                        "NASDAQ", AssetSignalAction.STRONG_BUY,
                        "GOLD", AssetSignalAction.BUY,
                        "COPPER", AssetSignalAction.BUY
                ),
                new NarrativeManualEvidence(4, 2),
                List.of(new NarrativeExternalSignal(
                        "YOUTUBE_30D",
                        "YouTube 30D",
                        600.0,
                        9,
                        "30D 600건"
                ))
        );
    }
}
