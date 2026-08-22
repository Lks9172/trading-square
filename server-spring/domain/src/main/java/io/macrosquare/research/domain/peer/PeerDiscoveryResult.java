package io.macrosquare.research.domain.peer;

import java.time.LocalDate;
import java.util.List;

public record PeerDiscoveryResult(
        LocalDate asOf,
        PeerTaxonomy target,
        List<PeerMatch> peers,
        int candidateCount,
        String methodology
) {
    public PeerDiscoveryResult {
        peers = List.copyOf(peers);
        methodology = methodology == null ? "" : methodology;
    }
}
