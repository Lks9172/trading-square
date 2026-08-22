package io.macrosquare.compatibility.adapter.out.earnings;

import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.Theme;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchCatalogEarningsUniverseAdapterTest {

    @Test
    void readsRawMembershipWithoutRotationAndAppliesCurrentTickerLifecycle() {
        var adapter = new ResearchCatalogEarningsUniverseAdapter(catalog(
                List.of("EA", "MMC", "RBLX"),
                List.of("CTRA", "MRSH", "EPD")
        ));

        assertEquals(Set.of("MRSH", "RBLX", "EPD"), adapter.loadTickers());
    }

    private static LoadResearchCatalogPort catalog(
            List<String> sectorTickers,
            List<String> themeTickers
    ) {
        return new LoadResearchCatalogPort() {
            @Override
            public ThemeCatalog loadThemes() {
                return new ThemeCatalog(List.of(new Theme(
                        "test-theme", "test", "test", themeTickers, List.of(), null
                )));
            }

            @Override
            public SectorCatalog loadSectors() {
                return new SectorCatalog(List.of(new Sector(
                        "test-sector", "test", "test", "SECTOR_TEST", sectorTickers,
                        null, null, null, List.of()
                )), null);
            }

            @Override
            public ThemeDetail loadTheme(String themeId, String sort, String companySort) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SectorDetail loadSector(String sectorId) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
