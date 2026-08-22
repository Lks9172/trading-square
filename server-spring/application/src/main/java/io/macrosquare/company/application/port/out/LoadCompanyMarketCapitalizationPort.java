package io.macrosquare.company.application.port.out;

import io.macrosquare.company.application.model.CompanyMarketCapitalization;

@FunctionalInterface
public interface LoadCompanyMarketCapitalizationPort {

    CompanyMarketCapitalization load(String normalizedTicker);
}
