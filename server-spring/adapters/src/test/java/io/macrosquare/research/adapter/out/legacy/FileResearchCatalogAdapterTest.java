package io.macrosquare.research.adapter.out.legacy;

import io.macrosquare.research.adapter.out.persistence.FileResearchCatalogAdapter;
import io.macrosquare.research.application.port.in.ResearchSectorNotFoundException;
import io.macrosquare.research.application.port.in.ResearchThemeNotFoundException;
import io.macrosquare.research.application.port.out.ResearchCatalogUnavailableException;
import io.macrosquare.shared.adapter.out.persistence.ReadOnlyJsonEnvelopeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileResearchCatalogAdapterTest {

    @TempDir
    Path directory;

    @Test
    void readsCompleteCatalogAndDetailRouteProjections() throws Exception {
        write("route_research-themes_v1.json", LegacyResearchCatalogFixture.THEMES_JSON);
        write("route_research-sectors_v6.json", LegacyResearchCatalogFixture.SECTORS_JSON);
        write("route_research-theme-detail_v1_ai-semiconductors_sort_quality_companysort_marketcap.json",
                LegacyResearchCatalogFixture.THEME_DETAIL_JSON);
        write("route_research-sector-detail_v1_technology.json",
                LegacyResearchCatalogFixture.SECTOR_DETAIL_JSON);
        var adapter = adapter();

        assertEquals("ai-semiconductors", adapter.loadThemes().themes().getFirst().id());
        assertEquals("technology", adapter.loadSectors().sectors().getFirst().id());
        assertEquals("quality", adapter.loadTheme("ai-semiconductors", "quality", "marketcap").sortKey());
        assertEquals("technology", adapter.loadSector("technology").sector().id());
    }

    @Test
    void upgradesRenamedSymbolsAndRemovesRetiredCompaniesFromCapturedCatalogs() throws Exception {
        write("route_research-themes_v1.json", LegacyResearchCatalogFixture.THEMES_JSON
                .replace("\"NVDA\", \"AMD\"", "\"MMC\", \"EA\", \"AMD\""));
        write("route_research-theme-detail_v1_ai-semiconductors_sort_quality_companysort_marketcap.json",
                LegacyResearchCatalogFixture.THEME_DETAIL_JSON
                        .replace("\"ticker\": \"NVDA\"", "\"ticker\": \"MMC\"")
                        .replace("\"ticker\": \"AMD\"", "\"ticker\": \"EA\""));
        var adapter = adapter();

        assertEquals(java.util.List.of("MRSH", "AMD"),
                adapter.loadThemes().themes().getFirst().tickers());
        assertEquals(java.util.List.of("MRSH"),
                adapter.loadTheme("ai-semiconductors", "quality", "marketcap").items().stream()
                        .map(item -> item.ticker()).toList());
    }

    @Test
    void replacesRetiredStandardSectorMembersWithoutCopyingTheirCapturedScores() throws Exception {
        var sectors = LegacyResearchCatalogFixture.SECTORS_JSON
                .replace("\"technology\"", "\"communication-services\"")
                .replace("\"MSFT\", \"AAPL\"", "\"GOOGL\", \"EA\"");
        var detail = LegacyResearchCatalogFixture.SECTOR_DETAIL_JSON
                .replace("\"technology\"", "\"communication-services\"")
                .replace("\"SECTOR_XLK\"", "\"SECTOR_XLC\"")
                .replace("\"MSFT\"", "\"GOOGL\", \"EA\"");
        write("route_research-sectors_v6.json", sectors);
        write("route_research-sector-detail_v1_communication-services.json", detail);
        var adapter = adapter();

        assertEquals(java.util.List.of("GOOGL", "RBLX"),
                adapter.loadSectors().sectors().getFirst().tickers());
        var sector = adapter.loadSector("communication-services");
        assertEquals(java.util.List.of("GOOGL", "RBLX"), sector.sector().tickers());
        assertEquals(java.util.List.of("RBLX"), sector.items().stream().map(item -> item.ticker()).toList());
        assertEquals("현재 Spring 기업 지표 계산 대기 중", sector.items().getFirst().error());
    }

    @Test
    void separatesUnknownIdsFromMissingKnownProjectionFiles() {
        var adapter = adapter();

        assertThrows(ResearchThemeNotFoundException.class,
                () -> adapter.loadTheme("missing", "buy", "priority"));
        assertThrows(ResearchSectorNotFoundException.class, () -> adapter.loadSector("missing"));
        assertThrows(ResearchCatalogUnavailableException.class,
                () -> adapter.loadTheme("ai-semiconductors", "buy", "priority"));
    }

    private FileResearchCatalogAdapter adapter() {
        return new FileResearchCatalogAdapter(new ReadOnlyJsonEnvelopeStore(
                new ObjectMapper(), directory.toAbsolutePath(), 1024 * 1024, 16));
    }

    private void write(String fileName, String value) throws Exception {
        Files.writeString(directory.resolve(fileName),
                "{\"key\":\"test\",\"updatedAt\":\"2026-07-20T00:00:00Z\",\"value\":" + value + "}");
    }
}
