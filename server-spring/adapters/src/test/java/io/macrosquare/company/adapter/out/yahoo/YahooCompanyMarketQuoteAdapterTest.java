package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.port.out.CompanyMarketQuoteUnavailableException;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooCompanyMarketQuoteAdapterTest {

    private static final URI PRIMARY = URI.create("https://query1.finance.test");
    private static final URI SECONDARY = URI.create("https://query2.finance.test");
    private static final String TEST_URL =
            "https://query1.finance.test/v8/finance/chart/TEST?range=5d&interval=1d";

    @Test
    void restoresTheNodeChartMetaContractAndCachesOnlyTheSuccessfulQuote() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(quoteJson("TEST", 50.25, 1_784_318_401L), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        var first = adapter.load(" test ");

        assertSame(first, adapter.load("TEST"));
        assertEquals("TEST", first.symbol());
        assertEquals(50.25, first.price());
        assertEquals(LocalDate.parse("2026-07-17"), first.date());
        server.verify();
    }

    @Test
    void appliesTheMmcCompatibilitySymbolAndFallsBackFromQueryOneToQueryTwo() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(
                        "https://query1.finance.test/v8/finance/chart/MRSH?range=5d&interval=1d"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(
                        "https://query2.finance.test/v8/finance/chart/MRSH?range=5d&interval=1d"))
                .andRespond(withSuccess(quoteJson("MRSH", 209.4, 1_784_318_401L), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        var quote = adapter.load("mmc");

        assertEquals("MRSH", quote.symbol());
        assertEquals(209.4, quote.price());
        server.verify();
    }

    @Test
    void malformedOrIncompleteYahooResponsesAreNeverCached() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo(TEST_URL))
                .andRespond(withSuccess("{\"chart\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        server.expect(twice(), requestTo(
                        "https://query2.finance.test/v8/finance/chart/TEST?range=5d&interval=1d"))
                .andRespond(withSuccess("{\"chart\":{\"result\":null}}", MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        assertThrows(CompanyMarketQuoteUnavailableException.class, () -> adapter.load("TEST"));
        assertThrows(CompanyMarketQuoteUnavailableException.class, () -> adapter.load("TEST"));
        server.verify();
    }

    @Test
    void mismatchedYahooSecurityIsRejectedBeforeItCanEnterTheTickerCache() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo(TEST_URL))
                .andRespond(withSuccess(quoteJson("WRONG", 50.25, 1_784_318_401L), MediaType.APPLICATION_JSON));
        server.expect(twice(), requestTo(
                        "https://query2.finance.test/v8/finance/chart/TEST?range=5d&interval=1d"))
                .andRespond(withSuccess(quoteJson("WRONG", 50.25, 1_784_318_401L), MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        assertThrows(CompanyMarketQuoteUnavailableException.class, () -> adapter.load("TEST"));
        assertThrows(CompanyMarketQuoteUnavailableException.class, () -> adapter.load("TEST"));

        server.verify();
    }

    @Test
    void servesUsableStaleQuoteImmediatelyAndSchedulesOnlyOneRefresh() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(quoteJson("TEST", 50.25, 1_784_318_401L), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(quoteJson("TEST", 51.75, 1_784_404_801L), MediaType.APPLICATION_JSON));
        var clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = adapter(builder, clock, executor);
        var initial = adapter.load("TEST");
        clock.advance(Duration.ofMinutes(2));

        assertSame(initial, adapter.load("TEST"));
        assertSame(initial, adapter.load("TEST"));
        assertEquals(1, executor.pendingTasks());
        executor.runNext();
        assertEquals(51.75, adapter.load("TEST").price());
        server.verify();
    }

    @Test
    void failedBackgroundRefreshNeverReplacesTheLastSuccessfulQuote() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(TEST_URL))
                .andRespond(withSuccess(quoteJson("TEST", 50.25, 1_784_318_401L), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(TEST_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(
                        "https://query2.finance.test/v8/finance/chart/TEST?range=5d&interval=1d"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        var clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = adapter(builder, clock, executor);
        var initial = adapter.load("TEST");
        clock.advance(Duration.ofMinutes(2));

        assertSame(initial, adapter.load("TEST"));
        executor.runNext();
        assertSame(initial, adapter.load("TEST"));
        server.verify();
    }

    @Test
    void coalescesConcurrentColdLoadsForTheSameTicker() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.expect(once(), requestTo(TEST_URL)).andRespond(request -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release Yahoo response");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return withSuccess(quoteJson("TEST", 50.25, 1_784_318_401L), MediaType.APPLICATION_JSON)
                    .createResponse(request);
        });
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> adapter.load("TEST"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> adapter.load(" test "));
            release.countDown();
            assertSame(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
        }
        server.verify();
    }

    @Test
    void rejectsANonPositiveExternalFetchLimit() {
        assertThrows(IllegalArgumentException.class, () -> new YahooCompanyMarketQuoteAdapter(
                RestClient.create(), new ObjectMapper(), List.of(PRIMARY, SECONDARY), Clock.systemUTC(),
                Duration.ofMinutes(1), Duration.ofMinutes(15), Runnable::run, 128, 0
        ));
    }

    private static YahooCompanyMarketQuoteAdapter adapter(
            RestClient.Builder builder,
            Clock clock,
            Executor executor
    ) {
        return new YahooCompanyMarketQuoteAdapter(
                builder.build(), new ObjectMapper(), List.of(PRIMARY, SECONDARY), clock,
                Duration.ofMinutes(1), Duration.ofMinutes(15), executor
        );
    }

    private static String quoteJson(String symbol, double price, long regularMarketTime) {
        return """
                {
                  "chart": {
                    "result": [{
                      "meta": {
                        "currency": "USD",
                        "symbol": "%s",
                        "regularMarketPrice": %s,
                        "regularMarketTime": %d
                      },
                      "timestamp": [1784318400],
                      "indicators": {"quote": [{"close": [%s]}]}
                    }],
                    "error": null
                  }
                }
                """.formatted(symbol, price, regularMarketTime, price);
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
