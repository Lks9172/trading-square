package io.macrosquare.company.domain.model;

/**
 * Semantic revenue dimensions discovered in a company filing.
 *
 * <p>The values deliberately describe business meaning rather than SEC/XBRL
 * taxonomy names so filing transport concerns remain outside the domain.</p>
 */
public enum CompanyRevenueMixDimension {
    REPORTABLE_SEGMENT("reportable-segment"),
    PRODUCT_OR_SERVICE("product-or-service"),
    GEOGRAPHY("geography");

    private final String value;

    CompanyRevenueMixDimension(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
