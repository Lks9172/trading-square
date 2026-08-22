package io.macrosquare.disclosure.application.port.out;

import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;
import io.macrosquare.disclosure.domain.model.DartDisclosure;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;

import java.time.Instant;
import java.util.List;

public interface DartRepository {
    int saveCompanies(List<DartCompany> companies, Instant collectedAt);

    int saveDisclosures(List<DartDisclosure> disclosures, Instant collectedAt);

    int saveFinancials(List<DartFinancialMetric> financials, Instant collectedAt);

    DartCompany findByStockCode(String stockCode);

    Instant companyDirectoryUpdatedAt();

    DartCompanySnapshot loadSnapshot(String stockCode, int disclosureLimit, int financialLimit);
}
