package io.macrosquare.company.application.port.out;

import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;

import java.util.List;

public interface LoadCompanyReadPort {

    SearchResult search(String normalizedQuery, int limit);

    SummaryResult summaries(List<String> normalizedTickers);

    Research detail(String normalizedTicker);
}
