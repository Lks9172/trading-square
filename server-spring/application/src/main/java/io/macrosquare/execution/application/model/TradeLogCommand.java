package io.macrosquare.execution.application.model;

import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TradeLogValue;

import java.util.Map;
import java.util.Objects;

public record TradeLogCommand(
        TradeLogKind kind,
        String asset,
        String from,
        String to,
        String notes,
        Map<String, TradeLogValue> context
) {
    public TradeLogCommand {
        kind = Objects.requireNonNull(kind, "kind");
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
