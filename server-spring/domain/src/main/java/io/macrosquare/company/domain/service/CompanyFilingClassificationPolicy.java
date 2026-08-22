package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFilingEvidence;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Compatibility policy for the legacy earnings-related filing classification.
 * Richer Item/Exhibit inspection is deliberately a later migration slice.
 */
public final class CompanyFilingClassificationPolicy {

    private static final Pattern EARNINGS_DESCRIPTION = Pattern.compile(
            "item 2\\.02|earnings|results of operations",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EARNINGS_ITEM = Pattern.compile(
            "(?:^|[,\\s])2\\.02(?:$|[,\\s])",
            Pattern.CASE_INSENSITIVE
    );

    public boolean isEarningsRelated(CompanyFilingEvidence filing) {
        Objects.requireNonNull(filing, "filing");
        if (!"8-K".equals(filing.form())) return false;
        var description = filing.primaryDocumentDescription();
        return description != null && EARNINGS_DESCRIPTION.matcher(description).find();
    }

    /**
     * Improved direct-discovery rule. Kept separate from the legacy compatibility
     * method so the existing submissions parity remains byte-for-byte stable.
     */
    public boolean isEarningsCandidate(CompanyFilingEvidence filing) {
        Objects.requireNonNull(filing, "filing");
        if (!"8-K".equals(filing.form())) return false;
        if (isEarningsRelated(filing)) return true;
        return filing.items() != null && EARNINGS_ITEM.matcher(filing.items()).find();
    }
}
