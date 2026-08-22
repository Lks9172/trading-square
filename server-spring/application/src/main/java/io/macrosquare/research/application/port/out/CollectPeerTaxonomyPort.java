package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.domain.peer.PeerTaxonomy;

import java.time.LocalDate;

@FunctionalInterface
public interface CollectPeerTaxonomyPort {
    PeerTaxonomy collect(PeerUniverseCompany company, LocalDate observedOn);
}
