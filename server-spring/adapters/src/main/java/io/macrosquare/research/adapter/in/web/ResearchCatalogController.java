package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.port.in.QueryResearchCatalogUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/research")
public final class ResearchCatalogController {

    private final QueryResearchCatalogUseCase queryCatalog;

    public ResearchCatalogController(QueryResearchCatalogUseCase queryCatalog) {
        this.queryCatalog = Objects.requireNonNull(queryCatalog);
    }

    @GetMapping("/themes")
    public ResearchCatalogApiResponse.ThemeCatalog listThemes() {
        return ResearchCatalogApiResponse.ThemeCatalog.from(queryCatalog.listThemes());
    }

    @GetMapping("/sectors")
    public ResearchCatalogApiResponse.SectorCatalog listSectors() {
        return ResearchCatalogApiResponse.SectorCatalog.from(queryCatalog.listSectors());
    }

    @GetMapping("/themes/{id}")
    public ResearchDetailApiResponse.ThemeDetail theme(
            @PathVariable String id,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String companySort
    ) {
        return ResearchDetailApiResponse.ThemeDetail.from(queryCatalog.getTheme(id, sort, companySort));
    }

    @GetMapping("/sectors/{id}")
    public ResearchDetailApiResponse.SectorDetail sector(@PathVariable String id) {
        return ResearchDetailApiResponse.SectorDetail.from(queryCatalog.getSector(id));
    }
}
