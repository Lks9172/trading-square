package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.model.ResearchCatalogModels.DensitySummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.CompanyItem;
import io.macrosquare.research.application.model.ResearchCatalogModels.RelatedTheme;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationCandidate;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDefinition;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.Theme;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDefinition;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.port.in.QueryResearchCatalogUseCase;
import io.macrosquare.research.application.port.in.ResearchSectorNotFoundException;
import io.macrosquare.research.application.port.in.ResearchThemeNotFoundException;
import io.macrosquare.research.application.port.in.CurrentSectorRotationUnavailableException;
import io.macrosquare.research.application.port.out.ResearchCatalogUnavailableException;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchCatalogControllerTest {

    @Test
    void preservesThemeAndSectorListContractsWithoutDroppingInformation() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchCatalogController(stub(false)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        var themesBody = mvc.perform(get("/api/research/themes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].id").value("ai-semiconductors"))
                .andExpect(jsonPath("$.themes[0].sectorSummary.averageVolumeConfirmationScore").doesNotExist())
                .andExpect(jsonPath("$.themes[0].sectorSummary.topSector.key").value("SECTOR_SOXX"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(themesBody.contains("\"averageVolumeConfirmationScore\":null"));
        assertFalse(themesBody.contains("\"avgVolumeConfirmationScore\""));

        var sectorsBody = mvc.perform(get("/api/research/sectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectors[0].densitySummary.peerPct").value(100))
                .andExpect(jsonPath("$.sectors[0].sectorSummary.topSector.avgVolumeConfirmationScore").value(55))
                .andExpect(jsonPath("$.rotation.nextCandidates[0].expectedLeadershipWindow").value("1_3m"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(sectorsBody.contains("\"averageVolumeConfirmationScore\":55"));
    }

    @Test
    void mapsLegacyCatalogFailuresToAStable502Contract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchCatalogController(stub(true)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/research/themes"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Legacy research catalog is temporarily unavailable"));
    }

    @Test
    void mapsInsufficientCurrentRotationEvidenceToAStable503Contract() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchCatalogController(rotationUnavailableStub()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/research/sectors"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error")
                        .value("Current sector rotation data is temporarily unavailable"));
    }

    @Test
    void preservesThemeAndSectorDetailContractsIncludingConditionalFields() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchCatalogController(stub(false)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        var themeBody = mvc.perform(get("/api/research/themes/ai-semiconductors")
                        .queryParam("sort", "quality")
                        .queryParam("companySort", "marketcap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme.id").value("ai-semiconductors"))
                .andExpect(jsonPath("$.sortKey").value("quality"))
                .andExpect(jsonPath("$.companySortKey").value("marketcap"))
                .andExpect(jsonPath("$.items[0].ticker").value("NVDA"))
                .andExpect(jsonPath("$.items[1].error").value("failed"))
                .andExpect(jsonPath("$.sectorScores[0].buyScoreTrend[2]").value(64))
                .andReturn().getResponse().getContentAsString();
        assertFalse(themeBody.contains("\"error\":null"));
        assertFalse(themeBody.contains("\"avgVolumeConfirmationScore\""));

        mvc.perform(get("/api/research/sectors/technology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sector.id").value("technology"))
                .andExpect(jsonPath("$.sortKey").value("priority"))
                .andExpect(jsonPath("$.sectorScores[0].avgVolumeConfirmationScore").value(55))
                .andExpect(jsonPath("$.rotationSummary.nextCandidates[0].sectorKey").value("SECTOR_IGF"));
    }

    @Test
    void preservesResearchDetailNotFoundBodies() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchCatalogController(stub(false)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/research/themes/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("theme not found"));
        mvc.perform(get("/api/research/sectors/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("sector not found"));
    }

    private static QueryResearchCatalogUseCase stub(boolean fail) {
        return new QueryResearchCatalogUseCase() {
            @Override
            public ThemeCatalog listThemes() {
                if (fail) throw new ResearchCatalogUnavailableException("failed");
                return themes();
            }

            @Override
            public SectorCatalog listSectors() {
                if (fail) throw new ResearchCatalogUnavailableException("failed");
                return sectors();
            }

            @Override
            public ThemeDetail getTheme(String themeId, String sort, String companySort) {
                if (fail) throw new ResearchCatalogUnavailableException("failed");
                if ("missing".equals(themeId)) throw new ResearchThemeNotFoundException();
                return themeDetail(sort == null ? "buy" : sort, companySort == null ? "priority" : companySort);
            }

            @Override
            public SectorDetail getSector(String sectorId) {
                if (fail) throw new ResearchCatalogUnavailableException("failed");
                if ("missing".equals(sectorId)) throw new ResearchSectorNotFoundException();
                return sectorDetail();
            }
        };
    }

    private static QueryResearchCatalogUseCase rotationUnavailableStub() {
        return new QueryResearchCatalogUseCase() {
            @Override
            public ThemeCatalog listThemes() {
                throw unavailable();
            }

            @Override
            public SectorCatalog listSectors() {
                throw unavailable();
            }

            @Override
            public ThemeDetail getTheme(String themeId, String sort, String companySort) {
                throw unavailable();
            }

            @Override
            public SectorDetail getSector(String sectorId) {
                throw unavailable();
            }

            private CurrentSectorRotationUnavailableException unavailable() {
                return new CurrentSectorRotationUnavailableException("insufficient point-in-time evidence");
            }
        };
    }

    private static ThemeCatalog themes() {
        return new ThemeCatalog(List.of(new Theme(
                "ai-semiconductors", "AI / 반도체", "AI CAPEX", List.of("NVDA"),
                List.of("SECTOR_SOXX"), new SectorSummary(66, 56, 42, null, 68, 37, 72, 77, score(null))
        )));
    }

    private static SectorCatalog sectors() {
        var rotationSector = new RotationSector(
                "SECTOR_XLK", "기술", "structural", 77, 75, 95, 65, 61, 71, 68, 54,
                "LEADING", "Leader", "now", "이미 주도 구간", List.of("리더십 확인")
        );
        var candidate = new RotationCandidate(
                "인프라", "SECTOR_IGF", 75, "IMPROVING", "Rotation In", "1_3m", "1~3개월 후보", "개선 중"
        );
        var rotation = new RotationSummary(
                "RE_ACCELERATION", 69, "재가속 단계", List.of("기술"), List.of("에너지"),
                List.of(), List.of(candidate), List.of(), List.of()
        );
        var sector = new Sector(
                "technology", "기술", "소프트웨어와 IT", "SECTOR_XLK", List.of("MSFT"),
                new SectorSummary(64, 56, 47, 55, 52, 18, 62, 77, score(55)),
                rotationSector,
                new DensitySummary(1, 100, 1, 100, 1, 100, 1, 100, 1, 100),
                List.of(new RelatedTheme("ai-semiconductors", "AI / 반도체"))
        );
        return new SectorCatalog(List.of(sector), rotation);
    }

    private static SectorScore score(Integer averageVolume) {
        return new SectorScore(
                averageVolume == null ? "SECTOR_SOXX" : "SECTOR_XLK",
                averageVolume == null ? "반도체" : "기술",
                "structural", -5.82, 62, 54, 68, 58, 72, 52, 18, 64,
                "선별 접근", "neutral", 77, "LEADING", "Leader", List.of("리더십 확인"),
                "바닥 시도", 56, 47, "대기", "초기 바닥 시도", averageVolume,
                averageVolume != null, null, null, null, false
        );
    }

    private static ThemeDetail themeDetail(String sort, String companySort) {
        var definition = new ThemeDefinition(
                "ai-semiconductors", "AI / 반도체", "AI CAPEX", List.of("NVDA", "FAIL"),
                List.of("SECTOR_SOXX")
        );
        var detailScore = detailScore(null, false);
        return new ThemeDetail(
                definition,
                List.of(company("NVDA", null, 1), company("FAIL", "failed", 2)),
                List.of(detailScore),
                new SectorSummary(64, 56, 47, null, 52, 18, 62, 77, detailScore),
                sort,
                companySort
        );
    }

    private static SectorDetail sectorDetail() {
        var catalog = sectors();
        var source = catalog.sectors().getFirst();
        var detailScore = detailScore(55, true);
        return new SectorDetail(
                new SectorDefinition(
                        source.id(), source.label(), source.description(), source.sectorKey(), source.tickers()
                ),
                "priority",
                source.relatedThemes(),
                List.of(detailScore),
                new SectorSummary(64, 56, 47, 55, 52, 18, 62, 77, detailScore),
                source.rotation(),
                catalog.rotation(),
                source.densitySummary(),
                List.of(company("MSFT", null, 1))
        );
    }

    private static SectorScore detailScore(Integer averageVolume, boolean averagePresent) {
        return new SectorScore(
                averagePresent ? "SECTOR_XLK" : "SECTOR_SOXX",
                averagePresent ? "기술" : "반도체",
                "structural", -5.82, 62, 54, 68, 58, 72, 52, 18, 64,
                "선별 접근", "neutral", 77, "LEADING", "Leader", List.of("리더십 확인"),
                "바닥 시도", 56, 47, "대기", "초기 바닥 시도", averageVolume,
                averagePresent, null, null, java.util.Arrays.asList(null, null, 64), true
        );
    }

    private static CompanyItem company(String ticker, String error, int rank) {
        return new CompanyItem(
                ticker, ticker, 1_000_000_000L, 75, 72, "BUY", 70, 28,
                12.4, null, 8.2, "SECTOR_XLK", 66, 64, 70, 30,
                "1차 확인", 76, "후보", rank, error
        );
    }
}
