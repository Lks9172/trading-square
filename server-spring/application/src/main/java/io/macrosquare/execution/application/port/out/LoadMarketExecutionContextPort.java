package io.macrosquare.execution.application.port.out;

import io.macrosquare.execution.application.model.MarketExecutionContext;

import java.util.Optional;

public interface LoadMarketExecutionContextPort {
    Optional<MarketExecutionContext> loadCurrent();
}
