package io.macrosquare.research.application.service;

import io.macrosquare.research.domain.rotation.MacroRegime;
import io.macrosquare.research.domain.rotation.RotationRegimeAssessment;
import io.macrosquare.research.domain.rotation.SectorRotationRegime;
import io.macrosquare.research.application.model.NarrativeHistoryPoint;
import io.macrosquare.research.application.model.NarrativeSnapshotMetadata;
import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.model.NarrativeTrend;
import io.macrosquare.research.application.model.ResearchSnapshot;
import io.macrosquare.research.application.port.in.NarrativeThemeNotFoundException;
import io.macrosquare.research.application.port.out.ResearchSnapshotUnavailableException;
import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.domain.narrative.AssetSignalAction;
import io.macrosquare.research.domain.narrative.NarrativeEvidence;
import io.macrosquare.research.domain.narrative.NarrativeExternalSignal;
import io.macrosquare.research.domain.narrative.NarrativeHeatPolicy;
import io.macrosquare.research.domain.narrative.NarrativeManualEvidence;
import io.macrosquare.research.domain.narrative.NarrativeSourceCoverageStatus;
import io.macrosquare.research.domain.narrative.NarrativeSourceObservation;
import io.macrosquare.research.domain.narrative.NarrativeSourcePolicy;
import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.research.domain.narrative.NarrativeThemeState;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryNarrativesServiceTest {

    private final NarrativeHeatPolicy policy = new NarrativeHeatPolicy();
    private final NarrativeThemeCatalog catalog = new NarrativeThemeCatalog();

    @Test
    void exposesThePinnedCatalogAndBuildsViewsFromRecomputedDomainState() {
        var snapshot = snapshot(false, true);
        var service = new QueryNarrativesService(() -> snapshot, policy, catalog);

        var definitions = service.listDefinitions();
        var overview = service.getOverview();
        var ai = service.getTheme(" ai-power ");

        assertEquals(List.of(
                "ai-power", "grid-capex", "defense-rearm", "finance-liquidity", "energy-supply",
                "digital-attention", "consumer-demand", "consumer-defensive", "materials-reflation",
                "real-assets-rate", "safehaven-gold"
        ), definitions.stream().map(definition -> definition.theme().id()).toList());
        assertEquals(11, overview.size());
        assertEquals("AI / 반도체", ai.definition().title());
        assertEquals("2026-07-19T00:00:00Z", ai.generatedAt());
        assertEquals(NarrativeTrend.STABLE, ai.trend());
        assertEquals("2026-07-18", ai.heatHistory().getFirst().date());
        assertEquals(60, ai.state().heatScore());
        assertEquals(70, snapshot.legacyNarratives().get(NarrativeTheme.AI_POWER).heatScore());
    }

    @Test
    void returnsNotFoundForUnknownThemeIds() {
        var service = new QueryNarrativesService(() -> snapshot(false, false), policy, catalog);

        assertThrows(NarrativeThemeNotFoundException.class, () -> service.getTheme("unknown"));
    }

    @Test
    void failsClosedWhenTheLegacyDefinitionDriftsFromThePinnedContract() {
        var service = new QueryNarrativesService(() -> snapshot(true, false), policy, catalog);

        assertThrows(ResearchSnapshotUnavailableException.class, service::getOverview);
    }

    @Test
    void prefersRevisionedNativeSourceEvidenceOverTheLegacyProxy() {
        var now = Instant.parse("2026-07-21T12:00:00Z");
        var reading = new NarrativeSourceReading(
                NarrativeTheme.AI_POWER, "GOOGLE_NEWS_7D", "Google News 7D",
                LocalDate.parse("2026-07-21"), now.minusSeconds(60), NarrativeSourceQuality.PUBLIC_FEED,
                NarrativeSourceStatus.AVAILABLE, 42d, 7d, "7D 42건", "https://news.google.com",
                "a".repeat(64), "raw/key");
        NarrativeSourceRepository repository = new NarrativeSourceRepository() {
            @Override
            public int save(List<NarrativeSourceReading> readings) {
                return 0;
            }

            @Override
            public List<NarrativeSourceObservation> loadSince(LocalDate since) {
                return List.of(new NarrativeSourceObservation(reading, 2));
            }
        };
        var service = new QueryNarrativesService(
                () -> snapshot(false, false), policy, catalog, repository,
                new NarrativeSourcePolicy(), new NarrativeSourceCatalog(), Clock.fixed(now, ZoneOffset.UTC));

        var view = service.getTheme("ai-power");

        assertEquals(NarrativeSourceCoverageStatus.DEGRADED, view.sourceAssessment().status());
        assertEquals(33, view.sourceAssessment().coveragePct());
        assertEquals(1, view.sourceAssessment().observationCount());
        assertEquals(1, view.sourceAssessment().revisionEventCount());
        assertEquals(1, view.sourceAssessment().history().size());
        assertEquals(2, view.state().externalSignals().stream()
                .filter(value -> value.key().equals("GOOGLE_NEWS_7D")).findFirst().orElseThrow().revision());
    }

    private ResearchSnapshot snapshot(boolean driftDefinition, boolean driftLegacyHeat) {
        var signals = Map.of(
                "NASDAQ", AssetSignalAction.BUY,
                "GOLD", AssetSignalAction.BUY,
                "COPPER", AssetSignalAction.STRONG_BUY
        );
        var external = List.of(new NarrativeExternalSignal(
                "YOUTUBE_30D", "YouTube Search", 100.0, 9, "검색 추정 100건"
        ));
        var evidence = new NarrativeEvidence(
                Map.of("VIXCLS", 20.0, "WTI", 70.0),
                Map.of("SECTOR_SOXX", 5.0, "NASDAQ_DISPARITY", 2.0),
                signals,
                NarrativeManualEvidence.empty(),
                external
        );
        var states = new EnumMap<NarrativeTheme, NarrativeThemeState>(NarrativeTheme.class);
        var metadata = new EnumMap<NarrativeTheme, NarrativeSnapshotMetadata>(NarrativeTheme.class);
        for (var theme : NarrativeTheme.values()) {
            var state = policy.evaluate(theme, evidence);
            if (driftLegacyHeat && theme == NarrativeTheme.AI_POWER) {
                state = new NarrativeThemeState(
                        state.theme(), state.stage(), state.heatScore() + 10,
                        state.drivers(), state.risks(), state.proxyScores(), state.externalSignals()
                );
            }
            states.put(theme, state);
            var definition = catalog.definition(theme);
            if (driftDefinition && theme == NarrativeTheme.AI_POWER) {
                definition = new NarrativeThemeDefinition(
                        theme,
                        "drifted title",
                        definition.description(),
                        definition.proxies(),
                        definition.externalQueries()
                );
            }
            metadata.put(theme, new NarrativeSnapshotMetadata(
                    definition,
                    "2026-07-19T00:00:00Z",
                    NarrativeTrend.STABLE,
                    1,
                    2,
                    List.of(new NarrativeHistoryPoint("2026-07-18", 55))
            ));
        }
        return new ResearchSnapshot(
                "2026-07-19T00:00:00Z",
                Map.of("VIXCLS", 20.0, "WTI", 70.0),
                Map.of("SECTOR_SOXX", 5.0, "NASDAQ_DISPARITY", 2.0),
                MacroRegime.NEUTRAL,
                signals,
                NarrativeManualEvidence.empty(),
                states,
                metadata,
                new RotationRegimeAssessment(
                        SectorRotationRegime.MID_GROWTH,
                        50,
                        Map.of(SectorRotationRegime.MID_GROWTH, 50)
                )
        );
    }
}
