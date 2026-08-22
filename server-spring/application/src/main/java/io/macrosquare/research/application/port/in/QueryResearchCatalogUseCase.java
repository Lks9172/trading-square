package io.macrosquare.research.application.port.in;

import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;

public interface QueryResearchCatalogUseCase {
    ThemeCatalog listThemes();

    SectorCatalog listSectors();

    ThemeDetail getTheme(String themeId, String sort, String companySort);

    SectorDetail getSector(String sectorId);
}
