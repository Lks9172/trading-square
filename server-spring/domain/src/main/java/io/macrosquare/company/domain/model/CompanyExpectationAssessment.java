package io.macrosquare.company.domain.model;

import java.util.List;

/**
 * Point-in-time interpretation of forward-EPS estimate changes.
 *
 * <p>This value deliberately excludes target-price upside movement. A rising
 * analyst target and a rising forward-EPS estimate are different pieces of
 * evidence and must never be substituted for one another.</p>
 */
public record CompanyExpectationAssessment(
        int score,
        State state,
        String summary,
        List<String> reasons,
        List<String> cautions
) {
    public CompanyExpectationAssessment {
        if (score < 0 || score > 100) throw new IllegalArgumentException("score must be between 0 and 100");
        if (state == null) throw new IllegalArgumentException("state is required");
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
    }

    public enum State {
        IMPROVING,
        STABLE,
        WEAKENING,
        DETERIORATING,
        UNAVAILABLE
    }
}
