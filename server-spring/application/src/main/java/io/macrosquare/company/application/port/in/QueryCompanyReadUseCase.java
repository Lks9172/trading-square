package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;

import java.util.List;

public interface QueryCompanyReadUseCase {

    SearchResult search(String query, int requestedLimit);

    SummaryResult summaries(List<String> tickers);

    Research detail(String ticker);
}
