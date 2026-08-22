package io.macrosquare.research.application.model;

import io.macrosquare.research.domain.rotation.SectorFundFlowEvidence;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthEvidence;

/** Latest persisted independent fund-flow and price-participation evidence. */
public record CurrentSectorMarketEvidence(
        SectorFundFlowEvidence fundFlow,
        SectorPriceBreadthEvidence priceBreadth
) {
    public boolean empty() {
        return fundFlow == null && priceBreadth == null;
    }
}
