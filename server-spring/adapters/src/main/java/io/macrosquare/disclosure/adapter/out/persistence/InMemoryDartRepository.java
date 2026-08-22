package io.macrosquare.disclosure.adapter.out.persistence;

import io.macrosquare.disclosure.application.port.out.DartRepository;
import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;
import io.macrosquare.disclosure.domain.model.DartDisclosure;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InMemoryDartRepository implements DartRepository {

    private final Map<String, DartCompany> companies = new LinkedHashMap<>();
    private final Map<String, DartDisclosure> disclosures = new LinkedHashMap<>();
    private final Map<String, DartFinancialMetric> financials = new LinkedHashMap<>();
    private Instant updatedAt;
    private Instant companyDirectoryUpdatedAt;

    @Override
    public synchronized int saveCompanies(List<DartCompany> values, Instant collectedAt) {
        values.forEach(value -> companies.put(value.stockCode(), value));
        updatedAt = collectedAt;
        companyDirectoryUpdatedAt = collectedAt;
        return values.size();
    }

    @Override
    public synchronized int saveDisclosures(List<DartDisclosure> values, Instant collectedAt) {
        values.forEach(value -> disclosures.put(value.receiptNumber(), value));
        updatedAt = collectedAt;
        return values.size();
    }

    @Override
    public synchronized int saveFinancials(List<DartFinancialMetric> values, Instant collectedAt) {
        values.forEach(value -> financials.put(key(value), value));
        updatedAt = collectedAt;
        return values.size();
    }

    @Override
    public synchronized DartCompany findByStockCode(String stockCode) {
        return companies.get(stockCode);
    }

    @Override
    public synchronized Instant companyDirectoryUpdatedAt() {
        return companyDirectoryUpdatedAt;
    }

    @Override
    public synchronized DartCompanySnapshot loadSnapshot(String stockCode, int disclosureLimit, int financialLimit) {
        var company = companies.get(stockCode);
        if (company == null) return empty();
        var companyDisclosures = disclosures.values().stream()
                .filter(value -> value.corpCode().equals(company.corpCode()))
                .sorted(Comparator.comparing(DartDisclosure::receivedOn).reversed()
                        .thenComparing(DartDisclosure::receiptNumber).reversed())
                .limit(disclosureLimit).toList();
        var companyFinancials = financials.values().stream()
                .filter(value -> value.corpCode().equals(company.corpCode()))
                .sorted(Comparator.comparingInt(DartFinancialMetric::businessYear).reversed()
                        .thenComparing(DartFinancialMetric::reportCode).reversed())
                .limit(financialLimit).toList();
        return new DartCompanySnapshot(
                "ready", updatedAt, company, companyDisclosures, companyFinancials,
                "OpenDART 공식 기업코드·공시목록·연결재무 전계정 API를 사용합니다.");
    }

    private DartCompanySnapshot empty() {
        return new DartCompanySnapshot(
                "collecting", updatedAt, null, List.of(), List.of(),
                "OpenDART API 키 활성화 후 기업코드와 공시를 수집합니다.");
    }

    private static String key(DartFinancialMetric value) {
        return value.corpCode() + '|' + value.businessYear() + '|' + value.reportCode() + '|'
                + value.statementCode() + '|' + value.accountId();
    }
}
