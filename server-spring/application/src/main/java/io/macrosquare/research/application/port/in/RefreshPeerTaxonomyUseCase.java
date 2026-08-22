package io.macrosquare.research.application.port.in;

import io.macrosquare.research.application.model.PeerTaxonomyRefreshReport;

@FunctionalInterface
public interface RefreshPeerTaxonomyUseCase {
    PeerTaxonomyRefreshReport refresh();
}
