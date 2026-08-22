package io.macrosquare.research.application.service;

import io.macrosquare.research.domain.rotation.MacroRegime;
import io.macrosquare.research.domain.rotation.RotationMarketEvidence;
import io.macrosquare.research.domain.rotation.SectorRotationPolicy;
import io.macrosquare.research.application.model.ResearchSnapshot;
import io.macrosquare.research.application.model.NarrativeHistoryPoint;
import io.macrosquare.research.application.model.NarrativeSnapshotMetadata;
import io.macrosquare.research.application.model.NarrativeTrend;
import io.macrosquare.research.application.port.out.LoadResearchSnapshotPort;
import io.macrosquare.research.domain.narrative.AssetSignalAction;
import io.macrosquare.research.domain.narrative.NarrativeEvidence;
import io.macrosquare.research.domain.narrative.NarrativeExternalSignal;
import io.macrosquare.research.domain.narrative.NarrativeHeatPolicy;
import io.macrosquare.research.domain.narrative.NarrativeManualEvidence;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.research.domain.narrative.NarrativeThemeState;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateResearchParityServiceTest {

    private final NarrativeHeatPolicy narrativePolicy = new NarrativeHeatPolicy();
    private final SectorRotationPolicy rotationPolicy = new SectorRotationPolicy();

    @Test
    void comparesNormalizedSnapshotInputsWithoutFrameworkOrTransportTypes() {
        var snapshot = matchingSnapshot();
        LoadResearchSnapshotPort port = () -> snapshot;
        var service = new EvaluateResearchParityService(port, narrativePolicy, rotationPolicy);

        var report = service.evaluate();

        assertTrue(report.allMatched());
        assertEquals(11, report.matchedNarratives());
        assertEquals(11, report.totalNarratives());
        assertTrue(report.rotation().matched());
        assertTrue(report.narratives().stream().allMatch(result -> result.differences().isEmpty()));
    }

    @Test
    void reportsTheExactMismatchedComponentInsteadOfHidingDrift() {
        var matching = matchingSnapshot();
        var narratives = new EnumMap<>(matching.legacyNarratives());
        var ai = narratives.get(NarrativeTheme.AI_POWER);
        narratives.put(NarrativeTheme.AI_POWER, new NarrativeThemeState(
                ai.theme(),
                ai.stage(),
                ai.heatScore() + 1,
                ai.drivers(),
                ai.risks(),
                ai.proxyScores(),
                ai.externalSignals()
        ));
        var drifted = new ResearchSnapshot(
                matching.timestamp(),
                matching.rawValues(),
                matching.derivedValues(),
                matching.macroRegime(),
                matching.assetSignals(),
                matching.manualEvidence(),
                narratives,
                matching.narrativeMetadata(),
                matching.legacyRotationAssessment()
        );
        var service = new EvaluateResearchParityService(() -> drifted, narrativePolicy, rotationPolicy);

        var report = service.evaluate();

        assertFalse(report.allMatched());
        assertEquals(10, report.matchedNarratives());
        var aiResult = report.narratives().stream()
                .filter(result -> result.theme() == NarrativeTheme.AI_POWER)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("heatScore"), aiResult.differences());
        assertEquals(86, aiResult.expectedHeatScore());
        assertEquals(85, aiResult.actualHeatScore());
    }

    private ResearchSnapshot matchingSnapshot() {
        var raw = new LinkedHashMap<String, Double>();
        raw.put("DXY", 100.0);
        raw.put("WTI", 68.0);
        raw.put("T10Y2Y", 0.6);
        raw.put("STLFSI4", -0.2);
        raw.put("BAMLH0A0HYM2", 3.4);
        raw.put("VIXCLS", 31.0);

        var derived = new LinkedHashMap<String, Double>();
        derived.put("LIQUIDITY_DIRECTION", 2.0);
        derived.put("REAL_YIELD", 1.2);
        derived.put("OVERHEATED", 0.0);
        derived.put("COPPER_GOLD_RATIO_UPTURN", 1.0);
        derived.put("CREDIT_HY_OAS_BP", 320.0);
        derived.put("INSTITUTIONAL_SECTOR_TECH_FLOW", 1.4);
        derived.put("INSTITUTIONAL_SECTOR_FIN_FLOW", 0.8);
        derived.put("INSTITUTIONAL_SECTOR_ENERGY_FLOW", -0.4);
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

        var signals = Map.of(
                "NASDAQ", AssetSignalAction.STRONG_BUY,
                "GOLD", AssetSignalAction.BUY,
                "COPPER", AssetSignalAction.BUY
        );
        var manual = new NarrativeManualEvidence(4, 2);
        var external = List.of(new NarrativeExternalSignal(
                "YOUTUBE_30D", "YouTube 30D", 600.0, 9, "30D 600건"
        ));
        var evidence = new NarrativeEvidence(raw, derived, signals, manual, external);
        var narratives = new EnumMap<NarrativeTheme, NarrativeThemeState>(NarrativeTheme.class);
        var metadata = new EnumMap<NarrativeTheme, NarrativeSnapshotMetadata>(NarrativeTheme.class);
        var catalog = new NarrativeThemeCatalog();
        for (var theme : NarrativeTheme.values()) {
            narratives.put(theme, narrativePolicy.evaluate(theme, evidence));
            metadata.put(theme, new NarrativeSnapshotMetadata(
                    catalog.definition(theme),
                    "2026-06-03T00:00:00Z",
                    NarrativeTrend.STABLE,
                    0,
                    0,
                    List.of(new NarrativeHistoryPoint("2026-06-03", 50))
            ));
        }
        var market = new RotationMarketEvidence(
                2.0, 1.2, 0.6, 68.0, 100.0, -0.2, 3.4, 320.0,
                false, true, MacroRegime.RISK_ON, 1.4, 0.8, -0.4
        );
        return new ResearchSnapshot(
                "2026-06-03T00:00:00Z",
                raw,
                derived,
                MacroRegime.RISK_ON,
                signals,
                manual,
                narratives,
                metadata,
                rotationPolicy.inferRegime(market)
        );
    }
}
