package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QueryResearchCatalogServiceTest {

    @Test
    void delegatesReadOnlyCatalogQueriesToTheOutboundPort() {
        var themes = new ThemeCatalog(List.of());
        var sectors = new SectorCatalog(List.of(), null);
        var captured = new java.util.ArrayList<String>();
        var service = new QueryResearchCatalogService(new LoadResearchCatalogPort() {
            @Override
            public ThemeCatalog loadThemes() {
                return themes;
            }

            @Override
            public SectorCatalog loadSectors() {
                return sectors;
            }

            @Override
            public ThemeDetail loadTheme(String themeId, String sort, String companySort) {
                captured.add(themeId);
                captured.add(sort);
                captured.add(companySort);
                return null;
            }

            @Override
            public SectorDetail loadSector(String sectorId) {
                captured.add(sectorId);
                return null;
            }
        });

        assertSame(themes, service.listThemes());
        assertSame(sectors, service.listSectors());
        assertNull(service.getTheme(" ai-semiconductors ", "unsupported", "marketcap"));
        assertNull(service.getSector(" technology "));
        assertEquals(List.of("ai-semiconductors", "buy", "marketcap", "technology"), captured);
    }
}
