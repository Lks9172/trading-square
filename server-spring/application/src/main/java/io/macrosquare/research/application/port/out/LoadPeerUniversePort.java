package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.PeerUniverseCompany;

import java.util.List;

@FunctionalInterface
public interface LoadPeerUniversePort {
    List<PeerUniverseCompany> load();
}
