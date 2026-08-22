package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;

public interface LoadResearchCatalogPort {
    ThemeCatalog loadThemes();

    SectorCatalog loadSectors();

    ThemeDetail loadTheme(String themeId, String sort, String companySort);

    SectorDetail loadSector(String sectorId);
}
