package io.macrosquare.company.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Filing-period freshness of the normalized fundamentals used for scoring.
 *
 * <p>The latest quote date is intentionally not part of this model. A current
 * price cannot make an older financial statement current.</p>
 */
public record CompanyFundamentalsFreshness(
        Status status,
        LocalDate fundamentalsAsOf,
        LocalDate latestPeriodicReportDate,
        LocalDate latestPeriodicFilingDate,
        String latestPeriodicForm,
        Integer lagDays,
        List<String> warnings
) {
    public CompanyFundamentalsFreshness {
        status = Objects.requireNonNull(status, "status");
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public boolean scoreComparable() {
        return status == Status.CURRENT;
    }

    public enum Status {
        CURRENT,
        LAGGING,
        INCOMPLETE,
        UNKNOWN
    }
}
