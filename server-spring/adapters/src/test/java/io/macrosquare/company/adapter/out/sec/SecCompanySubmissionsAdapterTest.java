package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
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
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecCompanySubmissionsAdapterTest {

    private static final String SUBMISSIONS_JSON = """
            {
              "cik": "1045810",
              "name": "NVIDIA CORP",
              "tickers": ["NVDA"],
              "exchanges": ["Nasdaq"],
              "sic": "3674",
              "filings": {
                "recent": {
                  "accessionNumber": ["0001045810-26-000060", "0001197647-26-000005", ""],
                  "filingDate": ["2026-07-02", "2026-07-06", "2026-07-01"],
                  "reportDate": ["2026-06-28", "", "2026-04-30"],
                  "form": ["8-K", "4", "10-Q"],
                  "primaryDocument": ["nvda-20260628.htm", "xslF345X06/wk-form4.xml", null],
                  "primaryDocDescription": ["Item 2.02 Results of Operations", "FORM 4", null],
                  "items": ["2.02,9.01", "", null]
                },
                "files": [{"name":"old-submissions.json"}]
              }
            }
            """;

    @Test
    void streamsOnlyTheConfiguredRecentColumnsAndBuildsExactArchiveUrls() throws Exception {
        var evidence = SecCompanySubmissionsMapper.map(
                new ObjectMapper().createParser(SUBMISSIONS_JSON),
                "CIK-1045810",
                20
        );

        assertEquals("0001045810", evidence.cik());
        assertEquals("NVIDIA CORP", evidence.name());
        assertEquals("NVDA", evidence.tickers().getFirst());
        assertEquals("Nasdaq", evidence.exchanges().getFirst());
        assertEquals("3674", evidence.sic());
        assertEquals(2, evidence.filings().size());
        assertEquals("Item 2.02 Results of Operations",
                evidence.filings().getFirst().primaryDocumentDescription());
        assertEquals("2.02,9.01", evidence.filings().getFirst().items());
        assertEquals(java.time.LocalDate.parse("2026-06-28"), evidence.filings().getFirst().reportDate());
        assertEquals(
                "https://www.sec.gov/Archives/edgar/data/1045810/000104581026000060/nvda-20260628.htm",
                evidence.filings().getFirst().sourceUrl()
        );
        assertEquals(
                "https://www.sec.gov/Archives/edgar/data/1045810/000119764726000005/xslF345X06/wk-form4.xml",
                evidence.filings().get(1).sourceUrl()
        );
    }

    @Test
    void retainsADeepPeriodicFilingBeyondThousandsOfOfferingFilings() throws Exception {
        var rows = 4_000;
        var accessions = new StringBuilder();
        var filingDates = new StringBuilder();
        var reportDates = new StringBuilder();
        var forms = new StringBuilder();
        var documents = new StringBuilder();
        for (var index = 0; index < rows; index++) {
            if (index > 0) {
                accessions.append(','); filingDates.append(','); reportDates.append(',');
                forms.append(','); documents.append(',');
            }
            accessions.append('"').append(String.format("0000000001-26-%06d", index)).append('"');
            filingDates.append('"').append(index == rows - 1 ? "2026-08-01" : "2026-08-05").append('"');
            reportDates.append('"').append(index == rows - 1 ? "2026-06-30" : "").append('"');
            forms.append('"').append(index == rows - 1 ? "10-Q" : "424B2").append('"');
            documents.append('"').append(index == rows - 1 ? "quarter.htm" : "offer.htm").append('"');
        }
        var json = """
                {"cik":"1","name":"BANK","tickers":["BANK"],"exchanges":["NYSE"],
                 "filings":{"recent":{
                   "accessionNumber":[%s],"filingDate":[%s],"reportDate":[%s],
                   "form":[%s],"primaryDocument":[%s]
                 }}}
                """.formatted(accessions, filingDates, reportDates, forms, documents);

        var evidence = SecCompanySubmissionsMapper.map(
                new ObjectMapper().createParser(json), "0000000001", 20);

        assertEquals(21, evidence.filings().size());
        var periodic = evidence.filings().getLast();
        assertEquals("10-Q", periodic.form());
        assertEquals(java.time.LocalDate.parse("2026-06-30"), periodic.reportDate());
        assertEquals("quarter.htm", periodic.primaryDocument());
    }

    @Test
    void preservesEmptyProfileValuesAndNullDocumentMetadataLikeTheNodeCollector() throws Exception {
        var json = """
                {"cik":1,"name":"","tickers":[],"exchanges":[""],"sic":null,
                 "filings":{"recent":{
                   "accessionNumber":["0000000001-26-000001"],"filingDate":["2026-07-17"],
                   "form":["8-K"],"primaryDocument":[null],"primaryDocDescription":[null]
                 }}}
                """;

        var evidence = SecCompanySubmissionsMapper.map(
                new ObjectMapper().createParser(json), "0000000001", 20
        );

        assertEquals("", evidence.name());
        assertEquals("", evidence.exchanges().getFirst());
        assertNull(evidence.sic());
        assertNull(evidence.filings().getFirst().primaryDocument());
        assertNull(evidence.filings().getFirst().sourceUrl());
    }

    @Test
    void normalizesCikAndCachesOnlySuccessfulSubmissions() {
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://data.sec.test/submissions/CIK0001045810.json"))
                .andRespond(withSuccess(SUBMISSIONS_JSON, MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        var first = adapter.load("CIK-1045810");

        assertSame(first, adapter.load("0001045810"));
        server.verify();
    }

    @Test
    void rejectsMalformedResponsesWithoutCachingThem() {
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo("https://data.sec.test/submissions/CIK0001045810.json"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        assertThrows(CompanySubmissionsUnavailableException.class, () -> adapter.load("1045810"));
        assertThrows(CompanySubmissionsUnavailableException.class, () -> adapter.load("1045810"));
        server.verify();
    }

    @Test
    void servesUsableStaleDataWhileSchedulingOnlyOneSuccessfulRefresh() {
        var refreshed = SUBMISSIONS_JSON.replace("NVIDIA CORP", "NVIDIA CORPORATION");
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://data.sec.test/submissions/CIK0001045810.json"))
                .andRespond(withSuccess(SUBMISSIONS_JSON, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://data.sec.test/submissions/CIK0001045810.json"))
                .andRespond(withSuccess(refreshed, MediaType.APPLICATION_JSON));
        var clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = adapter(builder, clock, executor);
        var initial = adapter.load("1045810");
        clock.advance(Duration.ofHours(5));

        assertSame(initial, adapter.load("1045810"));
        assertSame(initial, adapter.load("1045810"));
        assertEquals(1, executor.pendingTasks());
        executor.runNext();
        assertEquals("NVIDIA CORPORATION", adapter.load("1045810").name());
        server.verify();
    }

    @Test
    void rejectsNonPositiveFetchAndCacheBounds() {
        var restClient = RestClient.builder().baseUrl("https://data.sec.test").build();

        assertThrows(IllegalArgumentException.class, () -> new SecCompanySubmissionsAdapter(
                restClient, new ObjectMapper(), Clock.systemUTC(),
                Duration.ofHours(4), Duration.ofHours(24), Runnable::run,
                20, 128, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new SecCompanySubmissionsAdapter(
                restClient, new ObjectMapper(), Clock.systemUTC(),
                Duration.ofHours(4), Duration.ofHours(24), Runnable::run,
                20, 0, 2
        ));
    }

    private static SecCompanySubmissionsAdapter adapter(
            RestClient.Builder builder,
            Clock clock,
            Executor executor
    ) {
        return new SecCompanySubmissionsAdapter(
                builder.build(), new ObjectMapper(), clock,
                Duration.ofHours(4), Duration.ofHours(24), executor, 20, 2
        );
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
