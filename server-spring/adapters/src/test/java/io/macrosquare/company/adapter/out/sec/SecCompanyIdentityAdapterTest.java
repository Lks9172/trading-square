package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.port.in.CompanyTickerNotFoundException;
import io.macrosquare.company.application.port.out.CompanyIdentityUnavailableException;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecCompanyIdentityAdapterTest {

    private static final String DIRECTORY_JSON = """
            {
              "0": {"cik_str": 1067983, "ticker": "brk.b", "title": "Berkshire Hathaway Inc"},
              "1": {"cik_str": 1364886, "ticker": "SPR", "title": "Official SPR Wins"},
              "2": {"cik_str": 2115436, "ticker": "XOM", "title": "ExxonMobil Holdings Corp"}
            }
            """;

    @Test
    void restoresNodeTickerNormalizationCikPaddingAndMissingOnlyOverrides() throws Exception {
        var identities = SecCompanyIdentityMapper.map(new ObjectMapper().createParser(DIRECTORY_JSON));

        assertEquals("0001067983", identities.get("BRK-B").registryCik());
        assertEquals("Berkshire Hathaway Inc", identities.get("BRK-B").title());
        assertEquals("0001364886", identities.get("SPR").registryCik());
        assertEquals("Official SPR Wins", identities.get("SPR").title());
        assertEquals("0000004904", identities.get("AEP").registryCik());
        assertEquals("0001512673", identities.get("SQ").registryCik());
        assertEquals(List.of("0002115436", "0000034088"), identities.get("XOM").fundamentalsCiks());
        assertEquals(List.of("0002115436", "0000034088"), identities.get("XOM").submissionCiks());
    }

    @Test
    void resolvesDotAndHyphenAliasesFromOneSuccessOnlyDirectorySnapshot() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(withSuccess(DIRECTORY_JSON, MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        var dot = adapter.resolve(" brk.b ");

        assertSame(dot, adapter.resolve("BRK-B"));
        assertEquals("BRK-B", dot.ticker());
        assertThrows(CompanyTickerNotFoundException.class, () -> adapter.resolve("NOTREAL"));
        server.verify();
    }

    @Test
    void malformedDirectoriesAreNeverCached() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        assertThrows(CompanyIdentityUnavailableException.class, () -> adapter.resolve("BRK.B"));
        assertThrows(CompanyIdentityUnavailableException.class, () -> adapter.resolve("BRK.B"));
        server.verify();
    }

    @Test
    void servesStaleIdentityImmediatelyAndSchedulesOnlyOneRefresh() {
        var refreshed = DIRECTORY_JSON.replace("1067983", "1067984");
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(withSuccess(DIRECTORY_JSON, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(withSuccess(refreshed, MediaType.APPLICATION_JSON));
        var clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = adapter(builder, clock, executor);
        var initial = adapter.resolve("BRK.B");
        clock.advance(Duration.ofHours(25));

        assertSame(initial, adapter.resolve("BRK.B"));
        assertSame(initial, adapter.resolve("BRK.B"));
        assertEquals(1, executor.pendingTasks());
        executor.runNext();
        assertEquals("0001067984", adapter.resolve("BRK.B").registryCik());
        server.verify();
    }

    @Test
    void failedBackgroundRefreshNeverReplacesTheLastSuccessfulDirectory() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(withSuccess(DIRECTORY_JSON, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var clock = new MutableClock(Instant.parse("2026-07-19T00:00:00Z"));
        var executor = new QueuedExecutor();
        var adapter = adapter(builder, clock, executor);
        var initial = adapter.resolve("BRK.B");
        clock.advance(Duration.ofHours(25));

        assertSame(initial, adapter.resolve("BRK.B"));
        executor.runNext();
        assertSame(initial, adapter.resolve("BRK.B"));
        server.verify();
    }

    @Test
    void coalescesConcurrentColdLoadsIntoOneSecRequest() throws Exception {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.expect(once(), requestTo("https://www.sec.test/files/company_tickers.json"))
                .andRespond(request -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting to release SEC response");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    return withSuccess(DIRECTORY_JSON, MediaType.APPLICATION_JSON).createResponse(request);
                });
        var adapter = adapter(builder, Clock.systemUTC(), Runnable::run);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> adapter.resolve("BRK.B"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> adapter.resolve("BRK-B"));
            release.countDown();
            assertSame(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
        }
        server.verify();
    }

    private static SecCompanyIdentityAdapter adapter(
            RestClient.Builder builder,
            Clock clock,
            Executor executor
    ) {
        return new SecCompanyIdentityAdapter(
                builder.build(), new ObjectMapper(), clock,
                Duration.ofHours(24), Duration.ofDays(7), executor
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
