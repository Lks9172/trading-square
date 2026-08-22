package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.domain.peer.PeerTaxonomy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PeerTaxonomyRepository {
    int save(List<PeerTaxonomy> taxonomies, Instant refreshedAt);

    void reconcileDirectory(List<PeerUniverseCompany> universe, Instant observedAt, Duration missingGrace);

    Map<String, Instant> loadRefreshTimes();

    void markChecked(List<String> tickers, Instant checkedAt);

    PeerTaxonomy findAsOf(String ticker, LocalDate asOf);

    List<PeerTaxonomy> loadCandidates(PeerTaxonomy target, LocalDate asOf, int limit);
}
