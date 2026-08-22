package io.macrosquare.market.application.model;

import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;

/** Current top-down fragment calculated by an outer cross-context adapter. */
public record CurrentTopdownProjection(
        ObjectValue topdown,
        int currentMomentumCoverage,
        int universeSize
) {
}
