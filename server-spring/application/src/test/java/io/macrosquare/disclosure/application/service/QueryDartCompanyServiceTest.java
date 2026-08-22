package io.macrosquare.disclosure.application.service;

import io.macrosquare.disclosure.application.port.out.DartRepository;
import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartCompanySnapshot;
import io.macrosquare.disclosure.domain.model.DartDisclosure;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryDartCompanyServiceTest {

    @Test
    void distinguishesDisabledSourceFromAnActiveCollectorWaitingForItsFirstRun() {
        var service = new QueryDartCompanyService(new EmptyRepository(), false, false);

        var snapshot = service.query("005930");

        assertEquals("disabled", snapshot.status());
        assertTrue(snapshot.methodology().contains("비활성화"));
    }

    @Test
    void treatsMissingCredentialAsUnavailableRatherThanNeutralOrCollecting() {
        var service = new QueryDartCompanyService(new EmptyRepository(), true, false);

        var snapshot = service.query("005930");

        assertEquals("unavailable", snapshot.status());
        assertTrue(snapshot.methodology().contains("결측"));
    }

    private static final class EmptyRepository implements DartRepository {
        @Override public int saveCompanies(List<DartCompany> companies, Instant collectedAt) { return 0; }
        @Override public int saveDisclosures(List<DartDisclosure> disclosures, Instant collectedAt) { return 0; }
        @Override public int saveFinancials(List<DartFinancialMetric> financials, Instant collectedAt) { return 0; }
        @Override public DartCompany findByStockCode(String stockCode) { return null; }
        @Override public Instant companyDirectoryUpdatedAt() { return null; }
        @Override public DartCompanySnapshot loadSnapshot(String stockCode, int disclosureLimit, int financialLimit) {
            return new DartCompanySnapshot("collecting", null, null, List.of(), List.of(), "waiting");
        }
    }
}
