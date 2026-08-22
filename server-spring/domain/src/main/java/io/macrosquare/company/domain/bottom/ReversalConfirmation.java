package io.macrosquare.company.domain.bottom;

import java.time.LocalDate;
import java.util.List;

public record ReversalConfirmation(
        ReversalConfirmationStatus status,
        int score,
        LocalDate signalDate,
        String summary,
        List<String> reasons,
        List<String> cautions
) {
    public ReversalConfirmation {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
    }
}
