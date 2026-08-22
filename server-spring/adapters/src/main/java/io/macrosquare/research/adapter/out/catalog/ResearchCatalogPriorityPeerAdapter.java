package io.macrosquare.research.adapter.out.catalog;

import io.macrosquare.research.application.port.out.LoadPriorityPeerTickersPort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Prioritizes the visible product universe while the wider SEC universe backfills. */
public final class ResearchCatalogPriorityPeerAdapter implements LoadPriorityPeerTickersPort {

    private final LoadResearchCatalogPort catalog;

    public ResearchCatalogPriorityPeerAdapter(LoadResearchCatalogPort catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public Set<String> load() {
        var result = new LinkedHashSet<String>();
        catalog.loadSectors().sectors().forEach(sector -> sector.tickers().forEach(
                ticker -> result.add(ticker.toUpperCase(Locale.ROOT))));
        catalog.loadThemes().themes().forEach(theme -> theme.tickers().forEach(
                ticker -> result.add(ticker.toUpperCase(Locale.ROOT))));
        return Set.copyOf(result);
    }
}
