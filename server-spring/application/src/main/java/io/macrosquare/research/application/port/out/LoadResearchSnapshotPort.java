package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.ResearchSnapshot;

public interface LoadResearchSnapshotPort {
    ResearchSnapshot loadLatest();
}
