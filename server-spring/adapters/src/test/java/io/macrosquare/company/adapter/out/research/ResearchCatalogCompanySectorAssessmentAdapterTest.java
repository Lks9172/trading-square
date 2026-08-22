package io.macrosquare.company.adapter.out.research;

import io.macrosquare.research.application.model.ResearchCatalogModels.DensitySummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDefinition;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.service.QueryResearchCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchCatalogCompanySectorAssessmentAdapterTest {

    @Test
    void mapsCompanySectorAndCalculatesItsRelativeRotationRank() {
        var technology = sector("technology", "기술", "SECTOR_XLK", List.of("NVDA"), 76);
        var financials = sector("financials", "금융", "SECTOR_XLF", List.of("SCHW"), 72);
        var industrials = sector("industrials", "산업재", "SECTOR_XLI", List.of("CAT"), 70);
        var energy = sector("energy", "에너지", "SECTOR_XLE", List.of("XOM"), 60);
        var port = new StubCatalog(List.of(technology, financials, industrials, energy));
        var adapter = new ResearchCatalogCompanySectorAssessmentAdapter(
                new QueryResearchCatalogService(port));

        var result = adapter.load(" nvda ").orElseThrow();

        assertEquals("technology", result.sectorId());
        assertEquals(76, result.rotationScore());
        assertEquals(1, result.rotationRank());
        assertEquals(4, result.rotationUniverseSize());
        assertEquals(100, result.rotationPercentile());
        assertEquals("Leader", result.rotationLabel());
        assertEquals(null, result.earningsRevisionScore());
        assertEquals(72, result.referenceEarningsRevisionScore());
        assertEquals(68, result.flowScore());
        assertEquals(null, result.proxyFlowScore());

        var industrialResult = adapter.load("cat").orElseThrow();
        assertEquals(null, industrialResult.flowScore());
        assertEquals(68, industrialResult.proxyFlowScore());
        assertTrue(adapter.load("missing").isEmpty());
    }

    private static Sector sector(
            String id,
            String label,
            String key,
            List<String> tickers,
            int score
    ) {
        return new Sector(
                id,
                label,
                label + " 설명",
                key,
                tickers,
                null,
                rotation(key, label, score),
                new DensitySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of()
        );
    }

    private static RotationSector rotation(String key, String label, int score) {
        var officialFlow = java.util.Set.of("SECTOR_XLK", "SECTOR_XLF", "SECTOR_XLE").contains(key);
        return new RotationSector(
                key,
                label,
                "structural",
                score,
                75,
                80,
                70,
                65,
                72,
                null, null, null, null,
                68,
                officialFlow ? "2026-08-08" : null,
                officialFlow ? 1_000_000d : null,
                officialFlow ? 3_000_000d : null,
                officialFlow ? 5_000_000d : null,
                officialFlow ? 3d : null,
                officialFlow ? 5d : null,
                null, null, null, null, null, null,
                60,
                score >= 75 ? "LEADING" : "IMPROVING",
                score >= 75 ? "Leader" : "Rotation In",
                score >= 75 ? "now" : "1_3m",
                score >= 75 ? "현재 주도" : "다음 주도 후보",
                List.of("검증 근거")
        );
    }

    private static final class StubCatalog implements LoadResearchCatalogPort {
        private final List<Sector> sectors;

        private StubCatalog(List<Sector> sectors) {
            this.sectors = sectors;
        }

        @Override
        public ThemeCatalog loadThemes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SectorCatalog loadSectors() {
            return new SectorCatalog(sectors, null);
        }

        @Override
        public ThemeDetail loadTheme(String themeId, String sort, String companySort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SectorDetail loadSector(String sectorId) {
            var sector = sectors.stream()
                    .filter(value -> value.id().equals(sectorId))
                    .findFirst()
                    .orElseThrow();
            return new SectorDetail(
                    new SectorDefinition(
                            sector.id(), sector.label(), sector.description(),
                            sector.sectorKey(), sector.tickers()),
                    "buy",
                    List.of(),
                    List.of(),
                    null,
                    sector.rotation(),
                    null,
                    sector.densitySummary(),
                    List.of()
            );
        }
    }
}
