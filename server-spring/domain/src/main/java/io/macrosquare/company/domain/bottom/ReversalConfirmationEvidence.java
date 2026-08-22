package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.List;

public record ReversalConfirmationEvidence(
        DeepBottomSignal confirmedBottom,
        Integer technicalConfirmationScore,
        Integer priceStructureScore,
        BottomStructureState structureState,
        LocalDate confirmMarkerDate,
        List<String> bottomReasons,
        List<String> bottomCautions,
        List<String> failureSignals
) {
    public ReversalConfirmationEvidence {
        requireScore(technicalConfirmationScore, "technicalConfirmationScore");
        requireScore(priceStructureScore, "priceStructureScore");
        if (structureState == null) {
            throw new IllegalArgumentException("structureState is required");
        }
        bottomReasons = List.copyOf(bottomReasons == null ? List.of() : bottomReasons);
        bottomCautions = List.copyOf(bottomCautions == null ? List.of() : bottomCautions);
        failureSignals = failureSignals == null ? null : List.copyOf(failureSignals);
    }

    private static void requireScore(Integer value, String field) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }
}
