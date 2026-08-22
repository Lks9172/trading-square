package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooCompanyAnalystConsensusAdapterTest {

    private static final URI COOKIE_URL = URI.create("https://fc.yahoo.test");
    private static final URI CRUMB_URL = URI.create("https://query2.finance.test/v1/test/getcrumb");
    private static final URI SUMMARY_BASE = URI.create("https://query2.finance.test");
    private static final String COOKIE = "A3=alpha; GUC=beta";
    private static final String CRUMB = "testCrumb";
    private static final List<String> MEGACAP = List.of(
            "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA"
    );

    @Test
    void directlyCollectsTheSevenTickerYahooBatchAndCachesTheSuccessfulSnapshot() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        for (var ticker : MEGACAP) {
            server.expect(once(), requestTo(summaryUrl(ticker)))
                    .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                    .andRespond(withSuccess(
                            ticker.equals("AAPL") ? aaplJsonWithNonCurrentTrendFirst(ticker)
                                    : consensusJson(ticker, 318.25116, 333.74),
                            MediaType.APPLICATION_JSON
                    ));
        }
        var adapter = adapter(builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7), ticker -> {
            throw new AssertionError("persisted fallback must not be used after a successful live batch");
        });

        var aapl = adapter.load(" aapl ");
        var nvda = adapter.load("NVDA");

        assertEquals(0.66, aapl.analystScore());
        assertEquals(-4.64, aapl.upsidePct());
        assertEquals(5.26, aapl.epsEstimateRevision7dPct());
        assertEquals(25.0, aapl.epsEstimateRevision30dPct());
        assertEquals(-20.0, aapl.epsEstimateRevision90dPct());
        assertEquals(0.66, nvda.analystScore());
        server.verify();
    }

    @Test
    void directlyCollectsAndCachesANonCoreTickerWithoutTriggeringTheGlobalBatch() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        server.expect(once(), requestTo(summaryUrl("NEM")))
                .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                .andRespond(withSuccess(aaplJsonWithNonCurrentTrendFirst("NEM"), MediaType.APPLICATION_JSON));
        var fallbackCalls = new AtomicInteger();
        var adapter = adapter(builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7), ticker -> {
            fallbackCalls.incrementAndGet();
            return new CompanyAnalystConsensus(1.0, 10.0);
        });

        var result = adapter.load("NEM");
        var cached = adapter.load("NEM");

        assertEquals(0.66, result.analystScore());
        assertEquals(-4.64, result.upsidePct());
        assertEquals(25.0, result.epsEstimateRevision30dPct());
        assertEquals(result, cached);
        assertEquals(0, fallbackCalls.get());
        server.verify();
    }

    @Test
    void mapsTheRetainedMmcIdentityToTheCurrentMrshProviderSymbol() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        server.expect(once(), requestTo(summaryUrl("MRSH")))
                .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                .andRespond(withSuccess(aaplJsonWithNonCurrentTrendFirst("MRSH"), MediaType.APPLICATION_JSON));
        var adapter = adapter(
                builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7),
                ticker -> new CompanyAnalystConsensus(null, null)
        );

        var result = adapter.load("MMC");

        assertEquals(0.66, result.analystScore());
        assertEquals(-4.64, result.upsidePct());
        server.verify();
    }

    @Test
    void skipsFundsAndRetiredSecuritiesThatCannotHaveCompanyAnalystConsensus() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var fallbackCalls = new AtomicInteger();
        var adapter = adapter(
                builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7),
                ticker -> {
                    fallbackCalls.incrementAndGet();
                    return new CompanyAnalystConsensus(1.0, 10.0);
                }
        );

        for (var ticker : List.of("CTRA", "GLD", "IBIT")) {
            var result = adapter.load(ticker);
            assertNull(result.analystScore());
            assertNull(result.upsidePct());
        }

        assertEquals(0, fallbackCalls.get());
        server.verify();
    }

    @Test
    void keepsZeroAnalystCoverageAndSplitLikeTargetPricesUnavailable() throws Exception {
        var mapper = new ObjectMapper();
        var noCoverage = mapper.readTree(consensusJsonWithCounts("TEST", 110.0, 100.0, 0, 0, 0, 0, 0));
        var mismatchedPriceBasis = mapper.readTree(
                consensusJsonWithCounts("TEST", 1_000.0, 100.0, 2, 3, 1, 0, 0));

        var noCoverageResult = YahooCompanyAnalystConsensusMapper.map(noCoverage, "TEST");
        var mismatchResult = YahooCompanyAnalystConsensusMapper.map(mismatchedPriceBasis, "TEST");

        assertNull(noCoverageResult.analystScore());
        assertNull(mismatchResult.upsidePct());
        assertThrows(IllegalArgumentException.class,
                () -> YahooCompanyAnalystConsensusMapper.map(noCoverage, "WRONG"));
    }

    @Test
    void usesPersistedFallbackWhenANonCoreDirectFetchFails() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        server.expect(once(), requestTo(summaryUrl("NEM")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        var fallbackCalls = new AtomicInteger();
        var adapter = adapter(builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7), ticker -> {
            fallbackCalls.incrementAndGet();
            return new CompanyAnalystConsensus(1.0, 10.0);
        });

        var result = adapter.load("NEM");

        assertEquals(1.0, result.analystScore());
        assertEquals(10.0, result.upsidePct());
        assertNull(result.epsEstimateRevision30dPct());
        assertEquals(1, fallbackCalls.get());
        server.verify();
    }

    @Test
    void usesThePersistedSevenDayFallbackWhenFewerThanThreeLiveTickersSucceed() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        for (var index = 0; index < MEGACAP.size(); index++) {
            var ticker = MEGACAP.get(index);
            var expectation = server.expect(once(), requestTo(summaryUrl(ticker)))
                    .andExpect(header(HttpHeaders.COOKIE, COOKIE));
            if (index < 2) {
                expectation.andRespond(withSuccess(consensusJson(ticker, 110.0, 100.0), MediaType.APPLICATION_JSON));
            } else {
                expectation.andRespond(withStatus(HttpStatus.BAD_GATEWAY));
            }
        }
        var fallbackCalls = new AtomicInteger();
        var adapter = adapter(builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7), ticker -> {
            fallbackCalls.incrementAndGet();
            return new CompanyAnalystConsensus(0.5, 12.34);
        });

        var result = adapter.load("AAPL");

        assertEquals(0.5, result.analystScore());
        assertEquals(12.34, result.upsidePct());
        assertEquals(1, fallbackCalls.get());
        server.verify();
    }

    @Test
    void retainsAUsableDirectStaleSnapshotWhenTheHourlyRefreshFails() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        expectSuccessfulBatch(server);
        for (var ticker : MEGACAP) {
            server.expect(once(), requestTo(summaryUrl(ticker)))
                    .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        }
        var clock = new MutableClock(Instant.parse("2026-07-19T14:00:00Z"));
        var restClient = builder.build();
        var provider = new YahooFinanceAuthSessionProvider(
                restClient, COOKIE_URL, CRUMB_URL, clock, Duration.ofDays(7)
        );
        var adapter = new YahooCompanyAnalystConsensusAdapter(
                restClient, new ObjectMapper(), provider,
                ticker -> new CompanyAnalystConsensus(null, null), SUMMARY_BASE, clock,
                Duration.ofHours(1), Duration.ofDays(7), Duration.ZERO, 3
        );
        var initial = adapter.load("AAPL");
        clock.advance(Duration.ofHours(2));

        var stale = adapter.load("AAPL");

        assertEquals(initial, stale);
        assertEquals(-4.64, stale.upsidePct());
        server.verify();
    }

    @Test
    void coalescesConcurrentColdLoadsIntoOneGlobalSevenTickerBatch() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectAuth(server);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.expect(once(), requestTo(summaryUrl("AAPL")))
                .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                .andRespond(request -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to release Yahoo analyst response");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    return withSuccess(consensusJson("AAPL", 318.25116, 333.74), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        for (var ticker : MEGACAP.subList(1, MEGACAP.size())) {
            server.expect(once(), requestTo(summaryUrl(ticker)))
                    .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                    .andRespond(withSuccess(consensusJson(ticker, 318.25116, 333.74), MediaType.APPLICATION_JSON));
        }
        var adapter = adapter(
                builder.build(), Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7),
                ticker -> new CompanyAnalystConsensus(null, null)
        );

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> adapter.load("AAPL"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> adapter.load("MSFT"));
            release.countDown();
            assertEquals(0.66, first.get(3, TimeUnit.SECONDS).analystScore());
            assertEquals(0.66, second.get(3, TimeUnit.SECONDS).analystScore());
        }
        server.verify();
    }

    @Test
    void validatesCacheAndBatchConfiguration() {
        var client = RestClient.create();
        var provider = new YahooFinanceAuthSessionProvider(
                client, COOKIE_URL, CRUMB_URL, Clock.systemUTC(), Duration.ofHours(1)
        );
        assertThrows(IllegalArgumentException.class, () -> new YahooCompanyAnalystConsensusAdapter(
                client, new ObjectMapper(), provider, ticker -> new CompanyAnalystConsensus(null, null),
                SUMMARY_BASE, Clock.systemUTC(), Duration.ofDays(2), Duration.ofDays(1), Duration.ZERO, 3
        ));
        assertThrows(IllegalArgumentException.class, () -> new YahooCompanyAnalystConsensusAdapter(
                client, new ObjectMapper(), provider, ticker -> new CompanyAnalystConsensus(null, null),
                SUMMARY_BASE, Clock.systemUTC(), Duration.ofHours(1), Duration.ofDays(7), Duration.ZERO, 8
        ));
    }

    private static YahooCompanyAnalystConsensusAdapter adapter(
            RestClient restClient,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort fallback
    ) {
        var provider = new YahooFinanceAuthSessionProvider(
                restClient, COOKIE_URL, CRUMB_URL, clock, Duration.ofHours(1)
        );
        return new YahooCompanyAnalystConsensusAdapter(
                restClient, new ObjectMapper(), provider, fallback, SUMMARY_BASE, clock,
                cacheTtl, staleTtl, Duration.ZERO, 3
        );
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(COOKIE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .header(HttpHeaders.SET_COOKIE, "A3=alpha; Path=/; Secure", "GUC=beta; Path=/"));
        server.expect(once(), requestTo(CRUMB_URL))
                .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN_VALUE))
                .andRespond(withSuccess(CRUMB, MediaType.TEXT_PLAIN));
    }

    private static void expectSuccessfulBatch(MockRestServiceServer server) {
        for (var ticker : MEGACAP) {
            server.expect(once(), requestTo(summaryUrl(ticker)))
                    .andExpect(header(HttpHeaders.COOKIE, COOKIE))
                    .andRespond(withSuccess(consensusJson(ticker, 318.25116, 333.74), MediaType.APPLICATION_JSON));
        }
    }

    private static String summaryUrl(String ticker) {
        return SUMMARY_BASE + "/v10/finance/quoteSummary/" + ticker
                + "?modules=price,recommendationTrend,financialData,earningsTrend&crumb=" + CRUMB;
    }

    private static String consensusJson(String symbol, double targetMean, double currentPrice) {
        return consensusJsonWithCounts(symbol, targetMean, currentPrice, 6, 23, 15, 2, 1);
    }

    private static String consensusJsonWithCounts(
            String symbol,
            double targetMean,
            double currentPrice,
            int strongBuy,
            int buy,
            int hold,
            int sell,
            int strongSell
    ) {
        return """
                {
                  "quoteSummary": {
                    "result": [{
                      "price": {"symbol":"%s"},
                      "recommendationTrend": {"trend": [{
                        "period":"0m","strongBuy":%d,"buy":%d,"hold":%d,"sell":%d,"strongSell":%d
                      }]},
                      "financialData": {
                        "targetMeanPrice":{"raw":%s},
                        "currentPrice":{"raw":%s}
                      }
                    }],
                    "error": null
                  }
                }
                """.formatted(symbol, strongBuy, buy, hold, sell, strongSell, targetMean, currentPrice);
    }

    private static String aaplJsonWithNonCurrentTrendFirst(String symbol) {
        return """
                {
                  "quoteSummary": {
                    "result": [{
                      "price": {"symbol":"%s"},
                      "recommendationTrend": {"trend": [
                        {"period":"-1m","strongBuy":0,"buy":0,"hold":1,"sell":9,"strongSell":0},
                        {"period":"0m","strongBuy":6,"buy":23,"hold":15,"sell":2,"strongSell":1}
                      ]},
                      "financialData": {
                        "targetMeanPrice":{"raw":318.25116},
                        "currentPrice":{"raw":333.74}
                      },
                      "earningsTrend": {"trend": [{
                        "period":"0y",
                        "epsTrend": {
                          "current":{"raw":10.0},
                          "7daysAgo":{"raw":9.5},
                          "30daysAgo":{"raw":8.0},
                          "90daysAgo":{"raw":12.5}
                        }
                      }]}
                    }],
                    "error": null
                  }
                }
                """.formatted(symbol);
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
