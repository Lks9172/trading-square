package io.macrosquare.research.domain.rotation;

import java.util.List;

/** Confirmation is a checklist state, never a probability that the sector will rise. */
public record SectorLeadershipConfirmation(
        State state,
        int score,
        int evidenceCoveragePct,
        String label,
        List<String> reasons,
        List<String> invalidationSignals
) {
    public SectorLeadershipConfirmation {
        if (state == null || label == null || label.isBlank()) {
            throw new IllegalArgumentException("confirmation identity is required");
        }
        if (score < 0 || score > 100 || evidenceCoveragePct < 0 || evidenceCoveragePct > 100) {
            throw new IllegalArgumentException("confirmation score is invalid");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        invalidationSignals = List.copyOf(invalidationSignals == null ? List.of() : invalidationSignals);
    }

    public enum State {
        CONFIRMED,
        BUILDING,
        WATCH,
        INVALIDATED
    }
}
