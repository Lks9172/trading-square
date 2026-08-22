package io.macrosquare.market.application.port.out;

import io.macrosquare.market.application.model.CurrentMarketDecisionContext;
import io.macrosquare.market.application.model.CurrentTopdownProjection;

/** Anti-corruption port for research-context sector rotation composition. */
@FunctionalInterface
public interface EvaluateCurrentTopdownPort {
    CurrentTopdownProjection evaluate(CurrentMarketDecisionContext context);
}
