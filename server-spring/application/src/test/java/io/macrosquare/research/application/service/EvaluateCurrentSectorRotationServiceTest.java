package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.ResearchCatalogModels;
import io.macrosquare.research.application.model.CurrentSectorMarketEvidence;
import io.macrosquare.research.application.model.ResearchCatalogModels.DensitySummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.port.in.CurrentSectorRotationCommand;
import io.macrosquare.research.application.port.in.CurrentSectorRotationUnavailableException;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.domain.rotation.SectorFundFlowEvidence;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthEvidence;
import io.macrosquare.research.domain.rotation.SectorRotationPolicy;
import io.macrosquare.research.domain.rotation.SectorEarningsRevisionBreadth;
import io.macrosquare.research.domain.rotation.SectorEarningsRevisionBreadthPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCurrentSectorRotationServiceTest {

    @Test
    void overlaysCurrentMomentumInsteadOfReusingPersistedRotationScore() {
        var service = new EvaluateCurrentSectorRotationService(catalog(), new SectorRotationPolicy());
        var assessment = service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 100.0, "WTI", 70.0, "T10Y2Y", 0.4,
                        "STLFSI4", -0.5, "BAMLH0A0HYM2", 3.0),
                Map.of("SECTOR_REL_1M_XLK", 8.0, "SECTOR_RS_XLK", 12.0,
                        "SECTOR_MOMENTUM_SCORE_XLK", 85.0, "SECTOR_ABSOLUTE_TREND_XLK", 1.0,
                        "LIQUIDITY_DIRECTION", 1.0, "REAL_YIELD", 1.8,
                        "CREDIT_HY_OAS_BP", 300.0),
                "RISK_ON"));

        var technology = assessment.profiles().get("SECTOR_XLK");
        assertEquals(8.0, technology.shortTermRelativeStrength());
        assertEquals(12.0, technology.mediumTermRelativeStrength());
        assertEquals(1, assessment.currentMomentumCoverage());
        assertNotEquals(11, technology.rotation().rotationScore());
    }

    @Test
    void failsClosedWhenCurrentMomentumCoverageIsBelowSeventyPercent() {
        var service = new EvaluateCurrentSectorRotationService(
                catalog(score("SECTOR_XLK", "기술"), score("SECTOR_XLF", "금융")),
                new SectorRotationPolicy());

        assertThrows(CurrentSectorRotationUnavailableException.class,
                () -> service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 100.0),
                Map.of("SECTOR_REL_1M_XLK", 8.0, "SECTOR_RS_XLK", 12.0,
                        "SECTOR_MOMENTUM_SCORE_XLK", 85.0, "SECTOR_ABSOLUTE_TREND_XLK", 1.0),
                "RISK_ON")));
    }

    @Test
    void excludesIndividuallyUncoveredSectorInsteadOfRankingItsPersistedSeed() {
        var service = new EvaluateCurrentSectorRotationService(
                catalog(
                        score("SECTOR_XLK", "기술"), score("SECTOR_XLF", "금융"),
                        score("SECTOR_XLI", "산업재"), score("SECTOR_XLE", "에너지")),
                new SectorRotationPolicy());

        var assessment = service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 100.0, "WTI", 70.0, "T10Y2Y", 0.4,
                        "STLFSI4", -0.5, "BAMLH0A0HYM2", 3.0),
                Map.ofEntries(
                        Map.entry("SECTOR_REL_1M_XLK", 8.0), Map.entry("SECTOR_RS_XLK", 12.0),
                        Map.entry("SECTOR_MOMENTUM_SCORE_XLK", 90.0), Map.entry("SECTOR_ABSOLUTE_TREND_XLK", 1.0),
                        Map.entry("SECTOR_REL_1M_XLF", 4.0), Map.entry("SECTOR_RS_XLF", 6.0),
                        Map.entry("SECTOR_MOMENTUM_SCORE_XLF", 75.0), Map.entry("SECTOR_ABSOLUTE_TREND_XLF", 1.0),
                        Map.entry("SECTOR_REL_1M_XLI", 3.0), Map.entry("SECTOR_RS_XLI", 5.0),
                        Map.entry("SECTOR_MOMENTUM_SCORE_XLI", 65.0), Map.entry("SECTOR_ABSOLUTE_TREND_XLI", 1.0),
                        Map.entry("LIQUIDITY_DIRECTION", 1.0), Map.entry("REAL_YIELD", 1.8)),
                "RISK_ON"));

        assertEquals(3, assessment.currentMomentumCoverage());
        assertEquals(4, assessment.universeSize());
        assertFalse(assessment.profiles().containsKey("SECTOR_XLE"));
        assertFalse(assessment.rotation().sectors().stream()
                .anyMatch(item -> item.key().equals("SECTOR_XLE")));
    }

    @Test
    void failsClosedWhenMacroInputsAreTooSparseToInferARegime() {
        var service = new EvaluateCurrentSectorRotationService(catalog(), new SectorRotationPolicy());

        assertThrows(CurrentSectorRotationUnavailableException.class,
                () -> service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 100.0),
                Map.of("SECTOR_REL_1M_XLK", 8.0, "SECTOR_RS_XLK", 12.0,
                        "SECTOR_MOMENTUM_SCORE_XLK", 85.0, "SECTOR_ABSOLUTE_TREND_XLK", 1.0),
                "RISK_ON")));
    }

    @Test
    void referenceRevisionAndStyleFlowDoNotConfirmCurrentLeadership() {
        var port = catalog(score("SECTOR_XLI", "산업재"));
        var service = new EvaluateCurrentSectorRotationService(port, new SectorRotationPolicy());
        var assessment = service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 99.0, "WTI", 72.0, "T10Y2Y", 0.5,
                        "STLFSI4", -0.7, "BAMLH0A0HYM2", 2.8),
                Map.of("SECTOR_REL_1M_XLI", 9.0, "SECTOR_RS_XLI", 13.0,
                        "SECTOR_MOMENTUM_SCORE_XLI", 90.0, "SECTOR_ABSOLUTE_TREND_XLI", 1.0,
                        "LIQUIDITY_DIRECTION", 1.0, "REAL_YIELD", 1.5,
                        "CREDIT_HY_OAS_BP", 280.0),
                "RISK_ON"));

        var overlay = CurrentResearchCatalogOverlay.sectors(port.loadSectors(), assessment);
        var candidates = java.util.stream.Stream.of(
                        overlay.rotation().currentLeaders(), overlay.rotation().nextCandidates(),
                        overlay.rotation().secondaryCandidates(), overlay.rotation().fadingCandidates())
                .flatMap(java.util.Collection::stream)
                .toList();
        var industrial = candidates.stream()
                .filter(value -> value.sectorKey().equals("SECTOR_XLI"))
                .findFirst()
                .orElseThrow();

        assertNotEquals("CONFIRMED", industrial.confirmationState());
        assertEquals("WATCH", industrial.confirmationState());
        assertEquals(70, industrial.confirmationCoveragePct());
        assertTrue(industrial.confirmationReasons().stream()
                .anyMatch(value -> value.contains("이익추정 변화가 없어")));
        assertTrue(industrial.confirmationReasons().stream()
                .anyMatch(value -> value.contains("공식 ETF 생성·환매가 없습니다")));
    }

    @Test
    void usesOnlyDatedAndSufficientlyCoveredRevisionBreadth() {
        var port = catalog(score("SECTOR_XLI", "산업재"));
        var breadth = new SectorEarningsRevisionBreadth(
                LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-04"),
                LocalDate.parse("2026-08-05"), 5, 5, 4, 1, 0);
        var service = new EvaluateCurrentSectorRotationService(
                port,
                new SectorRotationPolicy(),
                (sectorKey, tickers, asOf, maxAge) -> java.util.Optional.of(breadth),
                new SectorEarningsRevisionBreadthPolicy());

        var assessment = service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 99.0, "WTI", 72.0, "T10Y2Y", 0.5,
                        "STLFSI4", -0.7, "BAMLH0A0HYM2", 2.8),
                Map.of("SECTOR_REL_1M_XLI", 9.0, "SECTOR_RS_XLI", 13.0,
                        "SECTOR_MOMENTUM_SCORE_XLI", 90.0, "SECTOR_ABSOLUTE_TREND_XLI", 1.0,
                        "LIQUIDITY_DIRECTION", 1.0, "REAL_YIELD", 1.5,
                        "CREDIT_HY_OAS_BP", 280.0),
                "RISK_ON"));

        var profile = assessment.profiles().get("SECTOR_XLI");
        assertEquals(80, profile.currentRevisionBreadth().score());
        assertEquals(100, profile.currentRevisionBreadth().coveragePct());
        assertEquals(80, profile.rotation().earningsRevisionScore());
        var overlay = CurrentResearchCatalogOverlay.sectors(port.loadSectors(), assessment);
        assertEquals("2026-08-05", overlay.sectors().getFirst().rotation().earningsRevisionObservedOn());
        assertEquals(80, overlay.sectors().getFirst().rotation().earningsRevisionUpPct());
    }

    @Test
    void exposesOnlyDatedOfficialFlowAndCoveredPriceBreadth() {
        var port = catalog(score("SECTOR_XLI", "산업재"));
        var repository = new SectorMarketEvidenceRepository() {
            @Override public void saveFundFlow(String key, String ticker, SectorFundFlowEvidence value,
                                                java.time.Instant at) { }
            @Override public void savePriceBreadth(String key, SectorPriceBreadthEvidence value,
                                                    java.time.Instant at) { }
            @Override public CurrentSectorMarketEvidence loadCurrent(String key, LocalDate asOf, int maxAge) {
                return new CurrentSectorMarketEvidence(
                        new SectorFundFlowEvidence(
                                LocalDate.parse("2026-08-05"), 100, 1_000_000, 100_000_000,
                                1_000_000, 3_000_000, 5_000_000, 3, 5, 68),
                        new SectorPriceBreadthEvidence(
                                LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-04"),
                                LocalDate.parse("2026-08-05"), 10, 10, 8, 7, 6, 67));
            }
        };
        var service = new EvaluateCurrentSectorRotationService(
                port, new SectorRotationPolicy(),
                io.macrosquare.research.application.port.out.LoadSectorEarningsRevisionBreadthPort.unavailable(),
                new SectorEarningsRevisionBreadthPolicy(), repository);

        var assessment = service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 99.0, "WTI", 72.0, "T10Y2Y", 0.5,
                        "STLFSI4", -0.7, "BAMLH0A0HYM2", 2.8),
                Map.of("SECTOR_REL_1M_XLI", 9.0, "SECTOR_RS_XLI", 13.0,
                        "SECTOR_MOMENTUM_SCORE_XLI", 90.0, "SECTOR_ABSOLUTE_TREND_XLI", 1.0,
                        "LIQUIDITY_DIRECTION", 1.0, "REAL_YIELD", 1.5,
                        "CREDIT_HY_OAS_BP", 280.0),
                "RISK_ON"));

        var profile = assessment.profiles().get("SECTOR_XLI");
        assertEquals(68, profile.currentFundFlow().score());
        assertEquals(67, profile.currentPriceBreadth().score());
        assertEquals(68, profile.rotation().flowScore());
        var rotation = CurrentResearchCatalogOverlay.sectors(port.loadSectors(), assessment)
                .sectors().getFirst().rotation();
        assertEquals("2026-08-05", rotation.fundFlowObservedOn());
        assertEquals(3_000_000d, rotation.fundFlow5dUsd());
        assertEquals("2026-08-05", rotation.priceBreadthObservedOn());
        assertEquals(60, rotation.aboveMa200Pct());
    }

    @Test
    void standardRotationSummaryNeverContainsOverlappingStrategicThemeEtfs() {
        var port = catalog(
                score("SECTOR_XLK", "기술"),
                score("SECTOR_XLF", "금융"),
                score("SECTOR_SOXX", "반도체"));
        var service = new EvaluateCurrentSectorRotationService(port, new SectorRotationPolicy());
        var assessment = service.evaluate(new CurrentSectorRotationCommand(
                "2026-08-05T12:00:00Z",
                Map.of("DXY", 100.0, "WTI", 70.0, "T10Y2Y", 0.4,
                        "STLFSI4", -0.5, "BAMLH0A0HYM2", 3.0),
                Map.ofEntries(
                        Map.entry("SECTOR_REL_1M_XLK", 4.0), Map.entry("SECTOR_RS_XLK", 8.0),
                        Map.entry("SECTOR_MOMENTUM_SCORE_XLK", 100.0), Map.entry("SECTOR_ABSOLUTE_TREND_XLK", 1.0),
                        Map.entry("SECTOR_REL_1M_XLF", 1.0), Map.entry("SECTOR_RS_XLF", 2.0),
                        Map.entry("SECTOR_MOMENTUM_SCORE_XLF", 0.0), Map.entry("SECTOR_ABSOLUTE_TREND_XLF", 1.0),
                        Map.entry("SECTOR_REL_1M_SOXX", 9.0), Map.entry("SECTOR_RS_SOXX", 20.0),
                        Map.entry("SECTOR_MOMENTUM_SCORE_SOXX", 50.0), Map.entry("SECTOR_ABSOLUTE_TREND_SOXX", 1.0),
                        Map.entry("LIQUIDITY_DIRECTION", 1.0), Map.entry("REAL_YIELD", 1.8),
                        Map.entry("CREDIT_HY_OAS_BP", 300.0)),
                "RISK_ON"));

        assertTrue(assessment.profiles().containsKey("SECTOR_SOXX"),
                "theme profile remains available to theme pages");
        assertEquals(2, assessment.currentMomentumCoverage());
        assertEquals(2, assessment.universeSize(),
                "standard-sector coverage metadata must not include overlapping themes");
        assertTrue(assessment.rotation().sectors().stream()
                .allMatch(item -> !item.key().equals("SECTOR_SOXX")));
        assertTrue(assessment.rotation().currentLeaders().stream()
                .allMatch(item -> !item.sectorKey().equals("SECTOR_SOXX")));
        assertFalse(assessment.rotation().summary().contains("반도체"));
    }

    @Test
    void thematicProjectionCannotReplaceCanonicalStandardSectorReference() {
        var canonical = score("SECTOR_XLK", "기술");
        var thematic = new SectorScore(
                "SECTOR_XLK", "테마가 덮어쓴 기술", "cyclical", 99.0,
                99, 99, 99, 99, 99, 99, 1, 99, "테마", "favored",
                99, "LEADING", "Leader", List.of("테마"), "후보", 99, 1,
                "진입", "테마", 99, true, null, null, null, false);
        var base = catalog(canonical);
        var port = new LoadResearchCatalogPort() {
            @Override public ThemeCatalog loadThemes() {
                return new ThemeCatalog(List.of(new ResearchCatalogModels.Theme(
                        "overlap", "중복 테마", "중복", List.of("MSFT"),
                        List.of("SECTOR_XLK"), null)));
            }
            @Override public SectorCatalog loadSectors() { return base.loadSectors(); }
            @Override public ThemeDetail loadTheme(String themeId, String sort, String companySort) {
                return new ThemeDetail(null, List.of(), List.of(thematic), null, sort, companySort);
            }
            @Override public SectorDetail loadSector(String sectorId) {
                throw new UnsupportedOperationException();
            }
        };

        var assessment = new EvaluateCurrentSectorRotationService(port, new SectorRotationPolicy())
                .evaluate(new CurrentSectorRotationCommand(
                        "2026-08-05T12:00:00Z",
                        Map.of("DXY", 100.0, "WTI", 70.0, "T10Y2Y", 0.4,
                                "STLFSI4", -0.5, "BAMLH0A0HYM2", 3.0),
                        Map.of("SECTOR_REL_1M_XLK", 8.0, "SECTOR_RS_XLK", 12.0,
                                "SECTOR_MOMENTUM_SCORE_XLK", 85.0,
                                "SECTOR_ABSOLUTE_TREND_XLK", 1.0,
                                "LIQUIDITY_DIRECTION", 1.0, "REAL_YIELD", 1.8,
                                "CREDIT_HY_OAS_BP", 300.0,
                                "OVERHEATED", 0.0, "COPPER_GOLD_RATIO_UPTURN", 0.0),
                        "RISK_ON"));

        assertEquals("기술", assessment.profiles().get("SECTOR_XLK").label());
        assertEquals(78, assessment.profiles().get("SECTOR_XLK").qualityScore());
        assertEquals("structural", assessment.profiles().get("SECTOR_XLK").classification());
    }

    private static LoadResearchCatalogPort catalog() {
        return catalog(score("SECTOR_XLK", "기술"));
    }

    private static SectorScore score(String key, String label) {
        return new SectorScore(
                key, label, "structural", -20.0, 78, 70, 80, 65, 70,
                68, 30, 72, "매수 우호", "favored", 11, "LAGGING", "Rotation Out",
                List.of("과거 값"), "미충족", 40, 45, "대기", "과거", 50,
                true, null, null, null, false);
    }

    private static LoadResearchCatalogPort catalog(SectorScore... scores) {
        var sectors = java.util.Arrays.stream(scores).map(score -> new Sector(
                score.key(), score.label(), score.label(), score.key(),
                List.of("MSFT", "AAPL", "NVDA", "ORCL", "CRM"),
                new SectorSummary(72, 40, 45, 50, 68, 30, 78, 11, score),
                rotation(score.key(), score.label()),
                new DensitySummary(1, 100, 1, 100, 1, 100, 1, 100, 1, 100), List.of()
        )).toList();
        return new LoadResearchCatalogPort() {
            @Override public ThemeCatalog loadThemes() { return new ThemeCatalog(List.of()); }
            @Override public SectorCatalog loadSectors() { return new SectorCatalog(sectors, null); }
            @Override public ThemeDetail loadTheme(String themeId, String sort, String companySort) {
                throw new UnsupportedOperationException();
            }
            @Override public SectorDetail loadSector(String sectorId) { throw new UnsupportedOperationException(); }
        };
    }

    private static RotationSector rotation(String key, String label) {
        return new RotationSector(
                key, label, "structural", 72, 75, 70, 74,
                64, 82, 66, 60, "IMPROVING", "Rotation In",
                "1_3m", "참고 순환값", List.of("저빈도 참고값"));
    }
}
