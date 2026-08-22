package io.macrosquare.research.application.port.in;

import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;

@FunctionalInterface
public interface CaptureSectorRotationSnapshotUseCase {
    boolean capture(CurrentSectorRotationAssessment assessment);

    static CaptureSectorRotationSnapshotUseCase unavailable() {
        return assessment -> false;
    }
}
