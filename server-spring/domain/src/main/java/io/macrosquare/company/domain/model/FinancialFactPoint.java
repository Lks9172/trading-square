package io.macrosquare.company.domain.model;

/**
 * A transport- and taxonomy-neutral financial observation.
 *
 * <p>SEC/XBRL tag names and units are resolved by the outbound adapter before
 * the observation enters the domain. The domain only needs the business value,
 * filing form, fiscal period, observation date, and optional period start.
 * Retaining the start date lets a pure domain policy distinguish standalone
 * quarters from year-to-date values without knowing XBRL frames.</p>
 */
public record FinancialFactPoint(
        double value,
        String form,
        String fiscalPeriod,
        String endDate,
        String startDate
) {
    public FinancialFactPoint {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("financial fact value must be finite");
    }

    /** Compatibility constructor for instant facts and callers without duration evidence. */
    public FinancialFactPoint(double value, String form, String fiscalPeriod, String endDate) {
        this(value, form, fiscalPeriod, endDate, null);
    }
}
