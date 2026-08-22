package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.port.out.CompanyFundamentalsUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecCompanyFactsAdapterTest {

    private static final String FACTS_JSON = """
            {
              "cik": 1,
              "facts": {
                "us-gaap": {
                  "RevenueFromContractWithCustomerExcludingAssessedTax": {
                    "units": {"USD": [
                      {"form":"10-K","fp":"FY","end":"2025-12-31","val":500},
                      {"form":"10-K","fp":"FY","end":"2024-12-31","val":400}
                    ]}
                  },
                  "SalesRevenueNet": {"units":{"USD":[{"end":"2026-12-31","val":999}]}},
                  "OperatingIncomeLoss": {"units":{"USD":[{"end":"2025-12-31","val":125}]}},
                  "LongTermDebt": {"units":{"USD":[{"end":"2025-12-31","val":90}]}},
                  "CashAndCashEquivalentsAtCarryingValue": {"units":{"USD":[{"end":"2025-12-31","val":150}]}},
                  "PaymentsToAcquirePropertyPlantAndEquipment": {"units":{"USD":[{"end":"2025-12-31","val":35}]}},
                  "CapitalExpendituresIncurredButNotYetPaid": {"units":{"USD":[{"end":"2026-03-31","val":999}]}},
                  "DebtInstrumentFaceAmount": {"units":{"USD":[{"end":"2026-03-31","val":999}]}},
                  "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest": {"units":{"USD":[{"end":"2025-12-31","val":100}]}},
                  "IncomeTaxExpenseBenefit": {"units":{"USD":[{"end":"2025-12-31","val":20}]}},
                  "Assets": {"units":{"USD":[{"end":"2025-12-31","val":1000}]}}
                },
                "dei": {
                  "EntityCommonStockSharesOutstanding": {"units":{"shares":[{"end":"2025-12-31","val":10}]}}
                }
              }
            }
            """;

    @Test
    void mapsSecTaxonomyToSemanticSeriesAndPrefersReportedFactsOverUndatedAlternatives() throws Exception {
        var mapper = new ObjectMapper();

        var evidence = SecCompanyFactsMapper.map(mapper.createParser(FACTS_JSON));

        assertEquals(2, evidence.revenue().size());
        assertEquals(500.0, evidence.revenue().getFirst().value());
        assertEquals(35.0, evidence.capitalExpenditure().getFirst().value());
        assertEquals(90.0, evidence.debt().getFirst().value());
        assertEquals(10.0, evidence.sharesOutstanding().getFirst().value());
        assertEquals(100.0, evidence.pretaxIncome().getFirst().value());
        assertEquals(20.0, evidence.incomeTaxExpense().getFirst().value());
        assertEquals(1_000.0, evidence.totalAssets().getFirst().value());
        assertThrows(IllegalArgumentException.class,
                () -> SecCompanyFactsMapper.map(mapper.createParser(FACTS_JSON), "2"));
    }

    @Test
    void retainsBoundedAnnualAndQuarterlyBusinessPeriodsForTtmAndQualityTrends() throws Exception {
        var quarters = new StringBuilder();
        for (var year = 2018; year <= 2025; year++) {
            if (!quarters.isEmpty()) quarters.append(',');
            quarters.append("{\"form\":\"10-Q\",\"fp\":\"Q1\",\"end\":\"")
                    .append(year).append("-03-31\",\"val\":").append(year).append('}');
        }
        var annuals = new StringBuilder();
        for (var year = 2018; year <= 2025; year++) {
            annuals.append(',').append("{\"form\":\"10-K\",\"fp\":\"FY\",\"end\":\"")
                    .append(year).append("-12-31\",\"val\":").append(year * 10).append('}');
        }
        var json = "{\"facts\":{\"us-gaap\":{\"RevenueFromContractWithCustomerExcludingAssessedTax\":"
                + "{\"units\":{\"USD\":[" + quarters + annuals + "]}}},\"dei\":{}}}";

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(8, evidence.revenue().size());
        assertEquals(List.of("2022-12-31", "2023-12-31", "2024-12-31", "2025-12-31"),
                evidence.revenue().stream()
                        .filter(point -> "10-K".equals(point.form()))
                        .map(point -> point.endDate())
                        .toList());
        assertEquals(List.of("2024-03-31", "2025-03-31", "2024-12-31", "2025-12-31"),
                evidence.revenue().stream()
                        .map(point -> point.endDate())
                        .filter(date -> date.startsWith("2024") || date.startsWith("2025"))
                        .toList());
    }

    @Test
    void filingDuplicatesDoNotConsumeTheFourDistinctAnnualPeriods() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[
                    {"form":"10-K","fp":"FY","end":"2022-12-31","val":100},
                    {"form":"10-K","fp":"FY","end":"2023-12-31","val":110},
                    {"form":"10-K","fp":"FY","end":"2024-12-31","val":120},
                    {"form":"10-K","fp":"FY","end":"2025-12-31","val":130},
                    {"form":"10-K","fp":"FY","end":"2025-12-31","val":131}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(4, evidence.revenue().size());
        assertEquals(131.0, evidence.revenue().stream()
                .filter(point -> "2025-12-31".equals(point.endDate()))
                .findFirst().orElseThrow().value());
    }

    @Test
    void rejectsDimensionallyWrongUnitsInsteadOfTreatingPerShareDataAsRevenue() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{
                    "USD":[],
                    "USD/shares":[{"form":"10-K","fp":"FY","end":"2025-12-31","val":7}]
                  }}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(0, evidence.revenue().size());
    }

    @Test
    void invalidStaleTagFallsBackToAUsableAlternativeTag() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{
                    "USD":[{"end":"2025-12-31","val":"not-a-number"}]
                  }},
                  "SalesRevenueNet":{"units":{"USD":[
                    {"form":"10-K","fp":"FY","end":"2025-12-31","val":999}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(1, evidence.revenue().size());
        assertEquals(999.0, evidence.revenue().getFirst().value());
    }

    @Test
    void selectsTheFreshestReportedRevenueTagAcrossTaxonomyTransitions() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[
                    {"start":"2021-01-01","end":"2021-12-31","form":"10-K","fp":"FY","val":100}
                  ]}},
                  "Revenues":{"units":{"USD":[
                    {"start":"2025-01-01","end":"2025-12-31","form":"10-K","fp":"FY","val":500}
                  ]}},
                  "WeightedAverageNumberOfDilutedSharesOutstanding":{"units":{"shares":[
                    {"start":"2025-01-01","end":"2025-12-31","form":"10-K","fp":"FY","val":25}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(500.0, evidence.revenue().getFirst().value());
        assertEquals("2025-01-01", evidence.revenue().getFirst().startDate());
        assertEquals(25.0, evidence.weightedAverageDilutedShares().getFirst().value());
    }

    @Test
    void doesNotReplaceCompleteRevenueHistoryWithOneSparseNewQuarter() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "Revenues":{"units":{"USD":[
                    {"start":"2024-04-01","end":"2025-03-31","form":"10-K","fp":"FY","val":740},
                    {"start":"2025-04-01","end":"2026-03-31","form":"10-K","fp":"FY","val":753}
                  ]}},
                  "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[
                    {"start":"2024-07-01","end":"2024-09-30","form":"10-Q","fp":"Q2","val":180},
                    {"start":"2024-10-01","end":"2024-12-31","form":"10-Q","fp":"Q3","val":181},
                    {"start":"2025-04-01","end":"2025-06-30","form":"10-Q","fp":"Q1","val":182},
                    {"start":"2025-07-01","end":"2025-09-30","form":"10-Q","fp":"Q2","val":183},
                    {"start":"2026-04-01","end":"2026-06-30","form":"10-Q","fp":"Q1","val":190}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(List.of("2025-03-31", "2026-03-31"), evidence.revenue().stream()
                .map(point -> point.endDate())
                .toList());
        assertEquals(753.0, evidence.revenue().stream()
                .filter(point -> "2026-03-31".equals(point.endDate()))
                .findFirst().orElseThrow().value());
    }

    @Test
    void mapsTotalCostsWithoutMistakingPartialLeaseIncomeForCompanyRevenue() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "OperatingLeaseLeaseIncome":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","form":"10-K","fp":"FY","val":300},
                    {"start":"2025-01-01","end":"2025-12-31","form":"10-K","fp":"FY","val":315}
                  ]}},
                  "CostsAndExpenses":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","form":"10-K","fp":"FY","val":70},
                    {"start":"2025-01-01","end":"2025-12-31","form":"10-K","fp":"FY","val":75}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(0, evidence.revenue().size());
        assertEquals(75.0, evidence.costsAndExpenses().stream()
                .filter(point -> "2025-12-31".equals(point.endDate()))
                .findFirst().orElseThrow().value());
    }

    @Test
    void mapsIncludingAssessedTaxRevenueUsedByCrowdStrikeAndOtherSaasIssuers() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "RevenueFromContractWithCustomerIncludingAssessedTax":{"units":{"USD":[
                    {"start":"2024-02-01","end":"2025-01-31","form":"10-K","fp":"FY","val":3950000000},
                    {"start":"2025-02-01","end":"2026-01-31","form":"10-K","fp":"FY","val":4800000000}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(2, evidence.revenue().size());
        assertEquals(4_800_000_000.0, evidence.revenue().stream()
                .filter(point -> "2026-01-31".equals(point.endDate()))
                .findFirst().orElseThrow().value());
    }

    @Test
    void mapsStandardBankRevenueNetOfInterestExpenseAsTotalRevenue() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "RevenuesNetOfInterestExpense":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","form":"10-K","fp":"FY","val":60000000000},
                    {"start":"2025-01-01","end":"2025-12-31","form":"10-K","fp":"FY","val":65000000000},
                    {"start":"2026-01-01","end":"2026-03-31","form":"10-Q","fp":"Q1","val":17000000000}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(3, evidence.revenue().size());
        assertEquals(17_000_000_000.0, evidence.revenue().stream()
                .filter(point -> "2026-03-31".equals(point.endDate()))
                .findFirst().orElseThrow().value());
    }

    @Test
    void retainsForeignPrivateIssuerAnnualFactsReportedOnForm20F() throws Exception {
        var json = """
                {"facts":{"us-gaap":{
                  "Revenues":{"units":{"USD":[
                    {"start":"2024-01-01","end":"2024-12-31","form":"20-F","fp":"FY","val":100},
                    {"start":"2025-01-01","end":"2025-12-31","form":"20-F","fp":"FY","val":125}
                  ]}}
                },"dei":{}}}
                """;

        var evidence = SecCompanyFactsMapper.map(new ObjectMapper().createParser(json));

        assertEquals(2, evidence.revenue().size());
        assertEquals(List.of("20-F", "20-F"), evidence.revenue().stream()
                .map(point -> point.form()).toList());
    }

    @Test
    void normalizesCikAndCachesOnlySuccessfulFacts() {
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://data.sec.test/api/xbrl/companyfacts/CIK0000000001.json"))
                .andRespond(withSuccess(FACTS_JSON, MediaType.APPLICATION_JSON));
        var adapter = new SecCompanyFactsAdapter(
                builder.build(), new ObjectMapper(), Clock.systemUTC(),
                Duration.ofHours(4), Duration.ofHours(24), Runnable::run
        );

        var first = adapter.load("CIK-1");

        assertSame(first, adapter.load("0000000001"));
        server.verify();
    }

    @Test
    void rejectsMalformedFactsWithoutCachingThem() {
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo("https://data.sec.test/api/xbrl/companyfacts/CIK0000000001.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var adapter = new SecCompanyFactsAdapter(
                builder.build(), new ObjectMapper(), Clock.systemUTC(),
                Duration.ofHours(4), Duration.ofHours(24), Runnable::run
        );

        assertThrows(CompanyFundamentalsUnavailableException.class, () -> adapter.load("1"));
        assertThrows(CompanyFundamentalsUnavailableException.class, () -> adapter.load("1"));
        server.verify();
    }

    @Test
    void rejectsNonPositiveConcurrentFetchLimit() {
        var restClient = RestClient.builder().baseUrl("https://data.sec.test").build();

        assertThrows(IllegalArgumentException.class, () -> new SecCompanyFactsAdapter(
                restClient,
                new ObjectMapper(),
                Clock.systemUTC(),
                Duration.ofHours(4),
                Duration.ofHours(24),
                Runnable::run,
                128,
                0
        ));
    }

    @Test
    void servesUsableStaleFactsImmediatelyWhileOneBackgroundRefreshRuns() {
        var refreshed = FACTS_JSON.replace("\"val\":500", "\"val\":600");
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://data.sec.test/api/xbrl/companyfacts/CIK0000000001.json"))
                .andRespond(withSuccess(FACTS_JSON, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://data.sec.test/api/xbrl/companyfacts/CIK0000000001.json"))
                .andRespond(withSuccess(refreshed, MediaType.APPLICATION_JSON));
        var clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = new SecCompanyFactsAdapter(
                builder.build(), new ObjectMapper(), clock,
                Duration.ofHours(4), Duration.ofHours(24), executor
        );
        var initial = adapter.load("1");
        clock.advance(Duration.ofHours(5));

        assertSame(initial, adapter.load("1"));
        assertSame(initial, adapter.load("1"));
        assertEquals(1, executor.pendingTasks());
        executor.runNext();
        assertEquals(600.0, adapter.load("1").revenue().getFirst().value());
        server.verify();
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int pendingTasks() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("only UTC is supported");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
