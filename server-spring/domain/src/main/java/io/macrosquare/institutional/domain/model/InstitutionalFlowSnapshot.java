package io.macrosquare.institutional.domain.model;

import java.time.LocalDate;
import java.util.List;

public record InstitutionalFlowSnapshot(
        LocalDate asOf,
        String source,
        int managerCount,
        int sharedPositionCount,
        int mappedPositionCount,
        int unmappedPositionCount,
        List<InstitutionalManagerFlow> managers,
        List<InstitutionalConsensus> consensus,
        List<InstitutionalDivergence> divergences
) {
    public InstitutionalFlowSnapshot {
        source = source == null ? "" : source;
        managers = List.copyOf(managers);
        consensus = List.copyOf(consensus);
        divergences = List.copyOf(divergences);
    }
}
