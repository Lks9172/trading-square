package io.macrosquare.compatibility.adapter.out.earnings;

import io.macrosquare.compatibility.application.port.out.LoadEarningsUniversePort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Anti-corruption adapter from the live research catalog to earnings symbols. */
public final class ResearchCatalogEarningsUniverseAdapter implements LoadEarningsUniversePort {

    private final LoadResearchCatalogPort catalog;

    public ResearchCatalogEarningsUniverseAdapter(LoadResearchCatalogPort catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public Set<String> loadTickers() {
        var tickers = new LinkedHashSet<String>();
        catalog.loadSectors().sectors().forEach(sector -> add(tickers, sector.tickers()));
        catalog.loadThemes().themes().forEach(theme -> add(tickers, theme.tickers()));
        if (tickers.isEmpty()) throw new IllegalStateException("earnings company universe is empty");
        return Set.copyOf(tickers);
    }

    private static void add(Set<String> target, java.util.List<String> source) {
        source.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .map(CurrentResearchUniverseTickerRegistry::canonicalTicker)
                .filter(value -> !CurrentResearchUniverseTickerRegistry.retired(value))
                .forEach(target::add);
    }
}
