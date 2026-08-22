package io.macrosquare.research.application.port.out;

import java.util.Set;

@FunctionalInterface
public interface LoadPriorityPeerTickersPort {
    Set<String> load();
}
