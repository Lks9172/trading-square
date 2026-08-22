package io.macrosquare.company.adapter.out.research;

import io.macrosquare.company.application.port.out.LoadCompanyAnalystUniversePort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Maps the standard-sector and strategic-theme catalogs to a deduplicated ticker universe. */
public final class ResearchCatalogCompanyAnalystUniverseAdapter implements LoadCompanyAnalystUniversePort {

    private final LoadResearchCatalogPort researchCatalog;

    public ResearchCatalogCompanyAnalystUniverseAdapter(LoadResearchCatalogPort researchCatalog) {
        this.researchCatalog = Objects.requireNonNull(researchCatalog);
    }

    @Override
    public List<String> loadTickers() {
        var tickers = new LinkedHashSet<String>();
        researchCatalog.loadSectors().sectors().forEach(sector -> add(tickers, sector.tickers()));
        researchCatalog.loadThemes().themes().forEach(theme -> add(tickers, theme.tickers()));
        return List.copyOf(tickers);
    }

    private static void add(LinkedHashSet<String> destination, List<String> source) {
        for (var ticker : source) {
            if (ticker == null || ticker.isBlank()) continue;
            var normalized = CurrentResearchUniverseTickerRegistry.canonicalTicker(ticker);
            if (!CurrentResearchUniverseTickerRegistry.retired(normalized)) destination.add(normalized);
        }
    }
}
