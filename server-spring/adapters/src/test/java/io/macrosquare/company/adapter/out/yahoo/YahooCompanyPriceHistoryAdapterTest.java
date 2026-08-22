package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.port.out.CompanyPriceHistoryUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooCompanyPriceHistoryAdapterTest {

    private static final URI PRIMARY = URI.create("https://query1.finance.test");
    private static final URI SECONDARY = URI.create("https://query2.finance.test");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private static final String TEST_URL = "https://query1.finance.test/v8/finance/chart/TEST"
            + "?period1=1751673600&period2=1784505600&interval=1d&events=div,splits";

    @Test
    void mapsAlignedDailyCloseAndVolumeArraysAndCachesOnlyValidHistory() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(historyJson("TEST"), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, CLOCK, Runnable::run);

        var history = adapter.load(" test ");

        assertSame(history, adapter.load("TEST"));
        assertEquals(2, history.size());
        assertEquals(LocalDate.parse("2026-07-15"), history.getFirst().date());
        assertEquals(50.25, history.getFirst().close());
        assertEquals(1_000_000.0, history.getFirst().volume());
        assertEquals(LocalDate.parse("2026-07-17"), history.getLast().date());
        assertEquals(52.75, history.getLast().close());
        assertEquals(1_100_000.0, history.getLast().volume());
        server.verify();
    }

    @Test
    void appliesMmcCompatibilityAndFallsBackFromQueryOneToQueryTwo() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var suffix = "/v8/finance/chart/MRSH?period1=1751673600&period2=1784505600&interval=1d&events=div,splits";
        server.expect(once(), requestTo(PRIMARY + suffix)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(SECONDARY + suffix))
                .andRespond(withSuccess(historyJson("MRSH"), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, CLOCK, Runnable::run);

        assertEquals(2, adapter.load("mmc").size());
        server.verify();
    }

    @Test
    void rejectsAProviderPayloadForADifferentSecurity() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(historyJson("WRONG"), MediaType.APPLICATION_JSON));
        var secondary = "https://query2.finance.test/v8/finance/chart/TEST"
                + "?period1=1751673600&period2=1784505600&interval=1d&events=div,splits";
        server.expect(once(), requestTo(secondary))
                .andRespond(withSuccess(historyJson("WRONG"), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, CLOCK, Runnable::run);

        assertThrows(CompanyPriceHistoryUnavailableException.class, () -> adapter.load("TEST"));
        server.verify();
    }

    @Test
    void malformedOrEmptyResponsesAreNeverCached() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo(TEST_URL))
                .andRespond(withSuccess("{\"chart\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        var secondary = "https://query2.finance.test/v8/finance/chart/TEST"
                + "?period1=1751673600&period2=1784505600&interval=1d&events=div,splits";
        server.expect(twice(), requestTo(secondary))
                .andRespond(withSuccess("{\"chart\":{\"result\":null}}", MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, CLOCK, Runnable::run);

        assertThrows(CompanyPriceHistoryUnavailableException.class, () -> adapter.load("TEST"));
        assertThrows(CompanyPriceHistoryUnavailableException.class, () -> adapter.load("TEST"));
        server.verify();
    }

    @Test
    void corporateActionBasisBreakIsNeverCachedAndAProviderCorrectionIsRetried() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var secondary = "https://query2.finance.test/v8/finance/chart/TEST"
                + "?period1=1751673600&period2=1784505600&interval=1d&events=div,splits";
        var unsafe = historyJson("TEST").replace("52.75", "25.125");
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(unsafe, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(secondary))
                .andRespond(withSuccess(unsafe, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(historyJson("TEST"), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, CLOCK, Runnable::run);

        assertThrows(CompanyPriceHistoryUnavailableException.class, () -> adapter.load("TEST"));
        assertEquals(52.75, adapter.load("TEST").getLast().close());
        server.verify();
    }

    @Test
    void normalizesANewlyEffectiveSplitButDoesNotDoubleAdjustRevisedHistory() {
        var pendingBuilder = RestClient.builder();
        var pendingServer = MockRestServiceServer.bindTo(pendingBuilder).build();
        pendingServer.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(splitHistoryJson(true), MediaType.APPLICATION_JSON));

        var normalized = adapter(pendingBuilder, CLOCK, Runnable::run).load("TEST");

        assertEquals(50, normalized.get(0).close());
        assertEquals(51, normalized.get(1).close());
        assertEquals(51, normalized.get(0).high());
        assertEquals(49, normalized.get(0).low());
        assertEquals(2_000_000, normalized.get(0).volume());
        assertEquals(51, normalized.get(2).close());
        pendingServer.verify();

        var revisedBuilder = RestClient.builder();
        var revisedServer = MockRestServiceServer.bindTo(revisedBuilder).build();
        revisedServer.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(splitHistoryJson(false), MediaType.APPLICATION_JSON));

        var alreadyRevised = adapter(revisedBuilder, CLOCK, Runnable::run).load("TEST");

        assertEquals(50, alreadyRevised.get(0).close());
        assertEquals(51, alreadyRevised.get(0).high());
        assertEquals(49, alreadyRevised.get(0).low());
        assertEquals(1_000_000, alreadyRevised.get(0).volume());
        revisedServer.verify();
    }

    @Test
    void splitMetadataAloneNeverDoubleAdjustsAnOrdinaryLargeMove() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(
                        splitHistoryJson(true)
                                .replace("100.0, 102.0", "70.0, 72.0")
                                .replace("102.0, 104.0", "73.0, 74.0")
                                .replace("98.0, 100.0", "68.0, 70.0"),
                        MediaType.APPLICATION_JSON
                ));

        var history = adapter(builder, CLOCK, Runnable::run).load("TEST");

        assertEquals(70, history.getFirst().close());
        assertEquals(1_000_000, history.getFirst().volume());
        server.verify();
    }

    @Test
    void servesStaleHistoryImmediatelyAndRefreshesItOnlyOnce() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(historyJson("TEST"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "https://query1.finance.test/v8/finance/chart/TEST"
                                + "?period1=1751674560&period2=1784506560&interval=1d&events=div,splits"))
                .andRespond(withSuccess(historyJson("TEST").replace("52.75", "53.75"), MediaType.APPLICATION_JSON));
        var clock = new MutableClock(Instant.parse("2026-07-20T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = adapter(builder, clock, executor);
        var initial = adapter.load("TEST");
        clock.advance(Duration.ofMinutes(16));

        assertSame(initial, adapter.load("TEST"));
        assertSame(initial, adapter.load("TEST"));
        assertEquals(1, executor.pendingTasks());
        executor.runNext();
        assertEquals(53.75, adapter.load("TEST").getLast().close());
        server.verify();
    }

    private static YahooCompanyPriceHistoryAdapter adapter(
            RestClient.Builder builder,
            Clock clock,
            Executor executor
    ) {
        return new YahooCompanyPriceHistoryAdapter(
                builder.build(), new ObjectMapper(), List.of(PRIMARY, SECONDARY), clock,
                380, Duration.ofMinutes(15), Duration.ofHours(2), executor, 8
        );
    }

    private static String historyJson(String symbol) {
        return """
                {
                  "chart": {
                    "result": [{
                      "meta": {"symbol": "%s"},
                      "timestamp": [1784145600, 1784232000, 1784318400, 1784404800],
                      "indicators": {
                        "quote": [{
                          "close": [50.25, null, 52.75, 53.0],
                          "volume": [1000000, 2000000, 1100000, null]
                        }]
                      }
                    }],
                    "error": null
                  }
                }
                """.formatted(symbol);
    }

    private static String splitHistoryJson(boolean pendingAdjustment) {
        var preSplitCloses = pendingAdjustment ? "100.0, 102.0" : "50.0, 51.0";
        return """
                {
                  "chart": {
                    "result": [{
                      "meta": {"symbol": "TEST"},
                      "timestamp": [1784145600, 1784232000, 1784318400, 1784404800],
                      "indicators": {
                        "quote": [{
                          "close": [%s, 51.0, 52.0],
                          "high": [%s, 52.0, 53.0],
                          "low": [%s, 50.0, 51.0],
                          "volume": [1000000, 1100000, 2200000, 2300000]
                        }]
                      },
                      "events": {
                        "splits": {
                          "1784318400": {
                            "date": 1784318400,
                            "numerator": 2.0,
                            "denominator": 1.0,
                            "splitRatio": "2:1"
                          }
                        }
                      }
                    }],
                    "error": null
                  }
                }
                """.formatted(
                        preSplitCloses,
                        pendingAdjustment ? "102.0, 104.0" : "51.0, 52.0",
                        pendingAdjustment ? "98.0, 100.0" : "49.0, 50.0"
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("only UTC is supported");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
