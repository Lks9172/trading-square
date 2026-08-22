package io.macrosquare.institutional.domain.model;

import java.time.LocalDate;
import java.util.List;

public record InstitutionalManagerFlow(
        InstitutionalManager manager,
        LocalDate reportPeriod,
        LocalDate previousReportPeriod,
        LocalDate filedOn,
        String sourceUrl,
        int holdingCount,
        double totalValueUsd,
        double previousTotalValueUsd,
        double netValueDeltaUsd,
        double estimatedNetFlowUsd,
        int newPositions,
        int increasedPositions,
        int reducedPositions,
        int exitedPositions,
        List<InstitutionalPositionFlow> topBuys,
        List<InstitutionalPositionFlow> topSells
) {
    public InstitutionalManagerFlow {
        topBuys = List.copyOf(topBuys);
        topSells = List.copyOf(topSells);
    }
}
