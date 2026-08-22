package io.macrosquare.execution.application.port.out;

import io.macrosquare.execution.domain.model.TradeLogEntry;

import java.util.List;

public interface TradeLogRepository {
    void append(TradeLogEntry entry);

    List<TradeLogEntry> recent(int limit);
}
