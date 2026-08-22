package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.adapter.out.json.ResearchCatalogJsonMapper;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.application.port.in.ResearchSectorNotFoundException;
import io.macrosquare.research.application.port.in.ResearchThemeNotFoundException;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.ResearchCatalogUnavailableException;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;

import java.util.Objects;
import java.util.Set;
import java.util.Comparator;
import java.util.List;

import io.macrosquare.research.application.model.ResearchCatalogModels.CompanyItem;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;

/** Reads complete research route projections from the crash-safe source cache. */
public final class FileResearchCatalogAdapter implements LoadResearchCatalogPort {

    private static final Set<String> THEME_IDS = Set.of(
            "ai-semiconductors", "megacap-platform-saas", "semiconductor-equipment",
            "power-infra", "defense-aerospace", "healthcare-biotech"
    );
    private static final Set<String> SECTOR_IDS = Set.of(
            "communication-services", "consumer-discretionary", "consumer-staples", "energy",
            "financials", "healthcare", "industrials", "materials", "real-estate", "technology", "utilities"
    );

    private final JsonEnvelopeStore store;

    public FileResearchCatalogAdapter(JsonEnvelopeStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public ThemeCatalog loadThemes() {
        return ResearchCatalogJsonMapper.mapThemes(required("route_research-themes_v1.json", "research themes"));
    }

    @Override
    public SectorCatalog loadSectors() {
        return ResearchCatalogJsonMapper.mapSectors(required("route_research-sectors_v6.json", "research sectors"));
    }

    @Override
    public ThemeDetail loadTheme(String themeId, String sort, String companySort) {
        if (!THEME_IDS.contains(themeId)) throw new ResearchThemeNotFoundException();
        var fileName = "route_research-theme-detail_v1_" + themeId
                + "_sort_" + sort + "_companysort_" + companySort + ".json";
        var exact = store.findValue(fileName);
        if (exact.isPresent()) return ResearchCatalogJsonMapper.mapThemeDetail(exact.get());

        var baseline = ResearchCatalogJsonMapper.mapThemeDetail(required(
                "route_research-theme-detail_v1_" + themeId + "_sort_buy_companysort_priority.json",
                "research theme " + themeId));
        var sectors = baseline.sectorScores().stream()
                .sorted(Comparator.comparingDouble((SectorScore value) -> sectorRank(value, sort)).reversed()
                        .thenComparing(SectorScore::key))
                .toList();
        var rankedCompanies = new java.util.ArrayList<CompanyItem>();
        var sortedCompanies = baseline.items().stream()
                .sorted(Comparator.comparingDouble((CompanyItem value) -> companyRank(value, companySort)).reversed()
                        .thenComparing(CompanyItem::ticker))
                .toList();
        for (var index = 0; index < sortedCompanies.size(); index++) {
            rankedCompanies.add(withRank(sortedCompanies.get(index), index + 1));
        }
        return new ThemeDetail(
                baseline.theme(), rankedCompanies, sectors, baseline.sectorSummary(), sort, companySort);
    }

    @Override
    public SectorDetail loadSector(String sectorId) {
        if (!SECTOR_IDS.contains(sectorId)) throw new ResearchSectorNotFoundException();
        return ResearchCatalogJsonMapper.mapSectorDetail(required(
                "route_research-sector-detail_v1_" + sectorId + ".json", "research sector " + sectorId));
    }

    private tools.jackson.databind.JsonNode required(String fileName, String label) {
        try {
            return store.findValue(fileName).orElseThrow(() ->
                    new ResearchCatalogUnavailableException("Persisted " + label + " projection is unavailable"));
        } catch (ResearchCatalogUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResearchCatalogUnavailableException("Unable to normalize persisted " + label, error);
        }
    }

    private static double sectorRank(SectorScore value, String sort) {
        return switch (sort) {
            case "quality" -> nullable(value.qualityScore(), -1);
            case "momentum" -> nullable(value.momentumScore(), -999);
            case "crowding" -> nullable(value.crowdingScore(), -1);
            case "delta7" -> nullable(value.buyScoreDelta7d(), -999);
            case "delta30" -> nullable(value.buyScoreDelta30d(), -999);
            default -> nullable(value.buyScore(), -1);
        };
    }

    private static double companyRank(CompanyItem value, String sort) {
        return switch (sort) {
            case "buy" -> nullable(value.buyScore(), -1);
            case "growth" -> nullable(value.revenueGrowthYoY(), -999);
            case "margin" -> nullable(value.operatingMargin(), -999);
            case "valuation" -> -nullable(value.evToSales(), 999);
            case "marketcap" -> nullable(value.marketCap(), -1);
            default -> priority(value);
        };
    }

    private static double priority(CompanyItem value) {
        return nullable(value.buyScore(), -1) * .45
                + nullable(value.totalScore(), -1) * .35
                + Math.min(100, Math.max(0, Math.log10(Math.max(1, nullable(value.marketCap(), 1))) * 8)) * .20;
    }

    private static double nullable(Number value, double fallback) {
        return value == null ? fallback : value.doubleValue();
    }

    private static CompanyItem withRank(CompanyItem value, int rank) {
        return new CompanyItem(
                value.ticker(), value.name(), value.marketCap(), value.totalScore(), value.buyScore(),
                value.buyLabel(), value.appealScore(), value.crowdingScore(), value.revenueGrowthYoY(),
                value.operatingMargin(), value.evToSales(), value.sectorKey(), value.bottomScore(),
                value.priceBottomScore(), value.volumeConfirmationScore(), value.failureRiskScore(),
                value.bottomState(), value.confirmedBottomScore(), value.confirmedBottomState(), rank, value.error());
    }
}
