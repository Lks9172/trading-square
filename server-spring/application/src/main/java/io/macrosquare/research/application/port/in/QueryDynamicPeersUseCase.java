package io.macrosquare.research.application.port.in;

import io.macrosquare.research.domain.peer.PeerDiscoveryResult;

import java.time.LocalDate;

@FunctionalInterface
public interface QueryDynamicPeersUseCase {
    PeerDiscoveryResult query(String ticker, LocalDate asOf, int limit);
}
