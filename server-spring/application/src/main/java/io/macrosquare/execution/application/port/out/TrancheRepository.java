package io.macrosquare.execution.application.port.out;

import io.macrosquare.execution.domain.model.TrancheEntry;

import java.util.List;

public interface TrancheRepository {
    List<TrancheEntry> append(TrancheEntry entry);

    List<TrancheEntry> findAll();

    List<TrancheEntry> clearAsset(String asset);
}
