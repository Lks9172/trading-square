package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.CurrentMarketDecisionContext;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;

/** Anti-corruption port for execution-context plan composition. */
@FunctionalInterface
public interface BuildCurrentExecutionPlansPort {
    ArrayValue build(CurrentMarketDecisionContext context);
}
