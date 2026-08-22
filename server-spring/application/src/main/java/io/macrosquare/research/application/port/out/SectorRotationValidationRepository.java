package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.rotation.PendingSectorRotationWindow;
import io.macrosquare.research.domain.rotation.SectorRotationCompositeSnapshot;
import io.macrosquare.research.domain.rotation.SectorRotationOutcome;

import java.util.List;

public interface SectorRotationValidationRepository {
    boolean append(SectorRotationCompositeSnapshot snapshot);

    List<PendingSectorRotationWindow> loadPendingWindows(int limit);

    int appendOutcomes(List<SectorRotationOutcome> outcomes);

    static SectorRotationValidationRepository unavailable() {
        return new SectorRotationValidationRepository() {
            @Override public boolean append(SectorRotationCompositeSnapshot snapshot) { return false; }
            @Override public List<PendingSectorRotationWindow> loadPendingWindows(int limit) { return List.of(); }
            @Override public int appendOutcomes(List<SectorRotationOutcome> outcomes) { return 0; }
        };
    }
}
