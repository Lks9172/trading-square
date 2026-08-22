package io.macrosquare.market.adapter.out.yahoo;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooMarketObservationAdapterTest {

    @Test
    void mapsPriceAndOptionalFiftyTwoWeekHighFromChartMetadata() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://query1.test/v8/finance/chart/%5EIXIC?range=5d&interval=1d"))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"meta":{"symbol":"^IXIC","regularMarketPrice":22791.11,
                        "regularMarketTime":1784505600,"fiftyTwoWeekHigh":23000.0}}]}}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new YahooMarketObservationAdapter(
                builder.build(),
                List.of(URI.create("https://query1.test")),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                Runnable::run,
                Map.of("NASDAQ", "^IXIC")
        );

        var batch = adapter.collect();

        assertEquals(2, batch.observations().size());
        assertEquals("NASDAQ_52WH", batch.observations().get(1).key());
        server.verify();
    }

    @Test
    void rejectsMismatchedOrStaleProviderMetadata() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(twice(), requestTo("https://query1.test/v8/finance/chart/%5EIXIC?range=5d&interval=1d"))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"meta":{"symbol":"^GSPC","regularMarketPrice":22791.11,
                        "regularMarketTime":1784505600}}]}}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new YahooMarketObservationAdapter(
                builder.build(), List.of(URI.create("https://query1.test")),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                Runnable::run, Map.of("NASDAQ", "^IXIC")
        );

        var batch = adapter.collect();

        assertEquals(0, batch.observations().size());
        assertEquals(1, batch.failures().size());
        server.verify();
    }

    @Test
    void retriesOneIncompleteProviderPassBeforePublishingASourceGap() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var url = "https://query1.test/v8/finance/chart/KRW%3DX?range=5d&interval=1d";
        server.expect(once(), requestTo(url))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"meta":{"symbol":"KRW=X"}}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(url))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"meta":{"symbol":"KRW=X","regularMarketPrice":1388.4,
                        "regularMarketTime":1784505600}}]}}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new YahooMarketObservationAdapter(
                builder.build(), List.of(URI.create("https://query1.test")),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                Runnable::run, Map.of("USDKRW", "KRW=X")
        );

        var batch = adapter.collect();

        assertEquals(1, batch.observations().size());
        assertEquals(0, batch.failures().size());
        assertEquals("USDKRW", batch.observations().getFirst().key());
        server.verify();
    }

    @Test
    void acceptsYahooExplicitUsdBaseAliasForRequestedFxShorthand() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(
                        "https://query1.test/v8/finance/chart/JPY%3DX?range=5d&interval=1d"))
                .andRespond(withSuccess("""
                        {"chart":{"result":[{"meta":{"symbol":"USDJPY=X",
                        "regularMarketPrice":157.745,"regularMarketTime":1784505600}}]}}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new YahooMarketObservationAdapter(
                builder.build(), List.of(URI.create("https://query1.test")),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                Runnable::run, Map.of("USDJPY", "JPY=X")
        );

        var batch = adapter.collect();

        assertEquals(1, batch.observations().size());
        assertEquals("USDJPY", batch.observations().getFirst().key());
        assertEquals(157.745, batch.observations().getFirst().value());
        assertEquals(0, batch.failures().size());
        server.verify();
    }
}
