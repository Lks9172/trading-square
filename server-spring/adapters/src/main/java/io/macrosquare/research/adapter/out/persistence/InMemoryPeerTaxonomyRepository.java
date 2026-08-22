package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.application.port.out.PeerTaxonomyRepository;
import io.macrosquare.research.domain.peer.PeerTaxonomy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryPeerTaxonomyRepository implements PeerTaxonomyRepository {

    private final List<PeerTaxonomy> values = new ArrayList<>();
    private final Map<String, Instant> refreshed = new HashMap<>();
    private final Map<String, Instant> directorySeen = new HashMap<>();

    @Override
    public synchronized int save(List<PeerTaxonomy> taxonomies, Instant refreshedAt) {
        for (var taxonomy : taxonomies) {
            var active = values.stream().filter(value -> value.ticker().equals(taxonomy.ticker())
                            && value.validTo() == null)
                    .max(Comparator.comparing(PeerTaxonomy::validFrom)).orElse(null);
            if (active != null && active.sic() == taxonomy.sic()) {
                values.remove(active);
                values.add(new PeerTaxonomy(
                        active.ticker(), taxonomy.cik(), taxonomy.companyName(), taxonomy.sic(),
                        taxonomy.sicDescription(), taxonomy.sectorKey(), active.validFrom(), null));
            } else {
                if (active != null && taxonomy.validFrom().isAfter(active.validFrom())) {
                    values.remove(active);
                    values.add(new PeerTaxonomy(
                            active.ticker(), active.cik(), active.companyName(), active.sic(),
                            active.sicDescription(), active.sectorKey(), active.validFrom(),
                            taxonomy.validFrom().minusDays(1)));
                }
                values.add(taxonomy);
            }
            refreshed.put(taxonomy.ticker(), refreshedAt);
        }
        return taxonomies.size();
    }

    @Override
    public synchronized void reconcileDirectory(
            List<PeerUniverseCompany> universe,
            Instant observedAt,
            Duration missingGrace
    ) {
        universe.forEach(value -> directorySeen.put(value.ticker(), observedAt));
        var cutoff = observedAt.minus(missingGrace);
        var retiredOn = LocalDate.ofInstant(observedAt, ZoneOffset.UTC);
        for (var entry : new ArrayList<>(directorySeen.entrySet())) {
            if (!entry.getValue().isBefore(cutoff)) continue;
            var active = values.stream().filter(value -> value.ticker().equals(entry.getKey())
                            && value.validTo() == null && !retiredOn.isBefore(value.validFrom()))
                    .toList();
            for (var value : active) {
                values.remove(value);
                values.add(new PeerTaxonomy(
                        value.ticker(), value.cik(), value.companyName(), value.sic(), value.sicDescription(),
                        value.sectorKey(), value.validFrom(), retiredOn));
            }
        }
    }

    @Override
    public synchronized Map<String, Instant> loadRefreshTimes() {
        return Map.copyOf(refreshed);
    }

    @Override
    public synchronized void markChecked(List<String> tickers, Instant checkedAt) {
        tickers.forEach(ticker -> refreshed.put(ticker, checkedAt));
    }

    @Override
    public synchronized PeerTaxonomy findAsOf(String ticker, LocalDate asOf) {
        return values.stream().filter(value -> value.ticker().equals(ticker) && value.activeOn(asOf))
                .max(Comparator.comparing(PeerTaxonomy::validFrom)).orElse(null);
    }

    @Override
    public synchronized List<PeerTaxonomy> loadCandidates(PeerTaxonomy target, LocalDate asOf, int limit) {
        return values.stream().filter(value -> value.activeOn(asOf))
                .filter(value -> value.sic() == target.sic() || value.sic() / 10 == target.sic() / 10
                        || value.sic() / 100 == target.sic() / 100
                        || value.sectorKey().equals(target.sectorKey()))
                .sorted(Comparator.comparing(PeerTaxonomy::ticker)).limit(limit).toList();
    }
}
