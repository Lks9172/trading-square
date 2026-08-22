package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.port.in.QueryResearchCatalogUseCase;
import io.macrosquare.research.application.port.in.CurrentSectorRotationCommand;
import io.macrosquare.research.application.port.in.EvaluateCurrentSectorRotationUseCase;
import io.macrosquare.research.application.port.in.ResearchSectorNotFoundException;
import io.macrosquare.research.application.port.in.ResearchThemeNotFoundException;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.LoadResearchSnapshotPort;
import io.macrosquare.research.application.port.out.LoadCurrentCompanyMetricsPort;

import java.util.Objects;

public final class QueryResearchCatalogService implements QueryResearchCatalogUseCase {

    private static final java.util.Set<String> SECTOR_SORTS = java.util.Set.of(
            "buy", "quality", "momentum", "crowding", "delta7", "delta30"
    );
    private static final java.util.Set<String> COMPANY_SORTS = java.util.Set.of(
            "priority", "buy", "growth", "margin", "valuation", "marketcap"
    );

    private final LoadResearchCatalogPort catalogPort;
    private final LoadResearchSnapshotPort snapshotPort;
    private final EvaluateCurrentSectorRotationUseCase currentRotation;
    private final LoadCurrentCompanyMetricsPort currentCompanyMetrics;

    public QueryResearchCatalogService(LoadResearchCatalogPort catalogPort) {
        this.catalogPort = Objects.requireNonNull(catalogPort);
        this.snapshotPort = null;
        this.currentRotation = null;
        this.currentCompanyMetrics = null;
    }

    public QueryResearchCatalogService(
            LoadResearchCatalogPort catalogPort,
            LoadResearchSnapshotPort snapshotPort,
            EvaluateCurrentSectorRotationUseCase currentRotation
    ) {
        this.catalogPort = Objects.requireNonNull(catalogPort);
        this.snapshotPort = Objects.requireNonNull(snapshotPort);
        this.currentRotation = Objects.requireNonNull(currentRotation);
        this.currentCompanyMetrics = null;
    }

    public QueryResearchCatalogService(
            LoadResearchCatalogPort catalogPort,
            LoadResearchSnapshotPort snapshotPort,
            EvaluateCurrentSectorRotationUseCase currentRotation,
            LoadCurrentCompanyMetricsPort currentCompanyMetrics
    ) {
        this.catalogPort = Objects.requireNonNull(catalogPort);
        this.snapshotPort = Objects.requireNonNull(snapshotPort);
        this.currentRotation = Objects.requireNonNull(currentRotation);
        this.currentCompanyMetrics = Objects.requireNonNull(currentCompanyMetrics);
    }

    @Override
    public ThemeCatalog listThemes() {
        var source = catalogPort.loadThemes();
        var assessment = assessment();
        return assessment == null ? source : CurrentResearchCatalogOverlay.themes(source, assessment);
    }

    @Override
    public SectorCatalog listSectors() {
        var source = catalogPort.loadSectors();
        var assessment = assessment();
        return assessment == null ? source : CurrentResearchCatalogOverlay.sectors(source, assessment);
    }

    @Override
    public ThemeDetail getTheme(String themeId, String sort, String companySort) {
        var id = requiredId(themeId, true);
        var source = catalogPort.loadTheme(id, normalize(sort, "buy", SECTOR_SORTS),
                normalize(companySort, "priority", COMPANY_SORTS));
        if (currentCompanyMetrics != null) {
            source = CurrentCompanyMetricOverlay.theme(source, currentCompanyMetrics.loadAll());
        }
        var assessment = assessment();
        return assessment == null ? source : CurrentResearchCatalogOverlay.theme(source, assessment);
    }

    @Override
    public SectorDetail getSector(String sectorId) {
        var source = catalogPort.loadSector(requiredId(sectorId, false));
        if (currentCompanyMetrics != null) {
            source = CurrentCompanyMetricOverlay.sector(source, currentCompanyMetrics.loadAll());
        }
        var assessment = assessment();
        return assessment == null ? source : CurrentResearchCatalogOverlay.sector(source, assessment);
    }

    private io.macrosquare.research.application.model.CurrentSectorRotationAssessment assessment() {
        if (snapshotPort == null || currentRotation == null) return null;
        var snapshot = snapshotPort.loadLatest();
        return currentRotation.evaluate(new CurrentSectorRotationCommand(
                snapshot.timestamp(), snapshot.rawValues(), snapshot.derivedValues(),
                snapshot.macroRegime().name()));
    }

    private static String requiredId(String value, boolean theme) {
        if (value == null || value.isBlank()) {
            if (theme) throw new ResearchThemeNotFoundException();
            throw new ResearchSectorNotFoundException();
        }
        return value.trim();
    }

    private static String normalize(String value, String fallback, java.util.Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}
