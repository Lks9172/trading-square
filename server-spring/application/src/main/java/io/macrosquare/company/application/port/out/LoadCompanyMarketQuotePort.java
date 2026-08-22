package io.macrosquare.company.application.port.out;

import io.macrosquare.company.application.model.CompanyMarketQuote;

/**
 * Loads one complete, read-only company quote. Implementations must fail
 * rather than returning a partially populated quote.
 */
@FunctionalInterface
public interface LoadCompanyMarketQuotePort {

    CompanyMarketQuote load(String normalizedTicker);
}
