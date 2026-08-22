package io.macrosquare.market.adapter.out.sentiment;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SentimentMarketObservationAdapterTest {

    @Test
    void collectsPutCallAaiiAndNaaimWithoutLeakingTransportTypes() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        for (var ticker : new String[]{"_SPX", "SPY", "QQQ"}) {
            var stale = "QQQ".equals(ticker);
            server.expect(once(), requestTo("https://cboe.test/api/options/" + ticker + ".json"))
                    .andRespond(withSuccess("""
                            {"timestamp":"%sT12:00:00Z","data":{"options":[
                              {"option":"TESTC12345678","volume":100},
                              {"option":"TESTP12345678","volume":%d}
                            ]}}
                            """.formatted(stale ? "2026-07-19" : "2026-07-20", stale ? 10_000 : 120),
                            MediaType.APPLICATION_JSON));
        }
        server.expect(once(), requestTo("https://aaii.test/feed"))
                .andRespond(withSuccess("""
                        <rss><channel><item><title><![CDATA[AAII Sentiment Survey: Test]]></title>
                        <pubDate>Sun, 19 Jul 2026 00:00:00 GMT</pubDate>
                        <content:encoded><![CDATA[
                          Bullish sentiment, expectations that prices will rise, increased 1.5 points to 40.5%.
                          Neutral sentiment decreased to 30.0%.
                          Bearish sentiment, expectations that prices will fall, decreased 2.0 points to 29.5%.
                        ]]></content:encoded>
                        </item></channel></rss>
                        """, MediaType.APPLICATION_XML));
        server.expect(once(), requestTo("https://naaim.test/exposure"))
                .andRespond(withSuccess("<table><tr><td>07/15/2026</td><td>82.45</td></tr></table>",
                        MediaType.TEXT_HTML));
        var adapter = new SentimentMarketObservationAdapter(
                builder.build(),
                new ObjectMapper(),
                URI.create("https://cboe.test/api/"),
                URI.create("https://aaii.test/feed"),
                URI.create("https://naaim.test/exposure"),
                Clock.fixed(Instant.parse("2026-07-20T13:00:00Z"), ZoneOffset.UTC),
                Runnable::run
        );

        var batch = adapter.collect();

        assertEquals(3, batch.observations().size());
        assertEquals(1.2, batch.observations().get(0).value());
        assertEquals("CBOE:CHAIN:2", batch.observations().get(0).providerCode());
        assertEquals(11.0, batch.observations().get(1).value());
        assertEquals(82.45, batch.observations().get(2).value());
        assertEquals(0, batch.failures().size());
        server.verify();
    }

    @Test
    void rejectsAnUndatedOptionChainInsteadOfStampingItWithCollectionDate() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        for (var ticker : new String[]{"_SPX", "SPY", "QQQ"}) {
            server.expect(once(), requestTo("https://cboe.test/api/options/" + ticker + ".json"))
                    .andRespond(withSuccess("""
                            {"data":{"options":[
                              {"option":"TESTC12345678","volume":100},
                              {"option":"TESTP12345678","volume":120}
                            ]}}
                            """, MediaType.APPLICATION_JSON));
        }
        server.expect(once(), requestTo("https://aaii.test/feed"))
                .andRespond(withSuccess("""
                        <rss><channel><item><title><![CDATA[AAII Sentiment Survey: Test]]></title>
                        <pubDate>Sun, 19 Jul 2026 00:00:00 GMT</pubDate>
                        <content:encoded><![CDATA[
                          Bullish sentiment, expectations that prices will rise, increased to 40.5%.
                          Bearish sentiment, expectations that prices will fall, decreased to 29.5%.
                        ]]></content:encoded></item></channel></rss>
                        """, MediaType.APPLICATION_XML));
        server.expect(once(), requestTo("https://naaim.test/exposure"))
                .andRespond(withSuccess("<table><tr><td>07/15/2026</td><td>82.45</td></tr></table>",
                        MediaType.TEXT_HTML));
        var adapter = new SentimentMarketObservationAdapter(
                builder.build(), new ObjectMapper(), URI.create("https://cboe.test/api/"),
                URI.create("https://aaii.test/feed"), URI.create("https://naaim.test/exposure"),
                Clock.fixed(Instant.parse("2026-07-20T13:00:00Z"), ZoneOffset.UTC), Runnable::run
        );

        var batch = adapter.collect();

        assertEquals(2, batch.observations().size());
        assertEquals("PC_RATIO", batch.failures().getFirst().key());
        server.verify();
    }

    @Test
    void reportsTheOfficialDelayedNaaimTableAsStaleInsteadOfMalformed() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        stubValidOptionChains(server);
        stubValidAaii(server);
        server.expect(once(), requestTo("https://naaim.test/exposure"))
                .andRespond(withSuccess("""
                        <table>
                          <tr><td>04/22/2026</td><td>94.15</td></tr>
                          <tr><td>04/29/2026</td><td>93.79</td></tr>
                        </table>
                        """, MediaType.TEXT_HTML));
        var adapter = new SentimentMarketObservationAdapter(
                builder.build(), new ObjectMapper(), URI.create("https://cboe.test/api/"),
                URI.create("https://aaii.test/feed"), URI.create("https://naaim.test/exposure"),
                Clock.fixed(Instant.parse("2026-08-09T02:00:00Z"), ZoneOffset.UTC), Runnable::run
        );

        var batch = adapter.collect();

        assertEquals(2, batch.observations().size());
        assertEquals(1, batch.failures().size());
        assertEquals("NAAIM_EXPOSURE", batch.failures().getFirst().key());
        assertEquals("Provider data is delayed beyond decision freshness",
                batch.failures().getFirst().reason());
        assertEquals(MarketCollectionBatch.FailureKind.PROVIDER_POLICY_UNAVAILABLE,
                batch.failures().getFirst().kind());
        server.verify();
    }

    @Test
    void choosesTheNewestNaaimRowEvenWhenTheProviderTableIsNotSorted() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        stubValidOptionChains(server);
        stubValidAaii(server);
        server.expect(once(), requestTo("https://naaim.test/exposure"))
                .andRespond(withSuccess("""
                        <table>
                          <tr><td>08/01/2026</td><td>70.00</td></tr>
                          <tr><td>08/05/2026</td><td>82.45</td></tr>
                        </table>
                        """, MediaType.TEXT_HTML));
        var adapter = new SentimentMarketObservationAdapter(
                builder.build(), new ObjectMapper(), URI.create("https://cboe.test/api/"),
                URI.create("https://aaii.test/feed"), URI.create("https://naaim.test/exposure"),
                Clock.fixed(Instant.parse("2026-08-09T02:00:00Z"), ZoneOffset.UTC), Runnable::run
        );

        var batch = adapter.collect();

        assertEquals(3, batch.observations().size());
        assertEquals(82.45, batch.observations().get(2).value());
        assertEquals(0, batch.failures().size());
        server.verify();
    }

    private static void stubValidOptionChains(MockRestServiceServer server) {
        for (var ticker : new String[]{"_SPX", "SPY", "QQQ"}) {
            server.expect(once(), requestTo("https://cboe.test/api/options/" + ticker + ".json"))
                    .andRespond(withSuccess("""
                            {"timestamp":"2026-08-08T12:00:00Z","data":{"options":[
                              {"option":"TESTC12345678","volume":100},
                              {"option":"TESTP12345678","volume":120}
                            ]}}
                            """, MediaType.APPLICATION_JSON));
        }
    }

    private static void stubValidAaii(MockRestServiceServer server) {
        server.expect(once(), requestTo("https://aaii.test/feed"))
                .andRespond(withSuccess("""
                        <rss><channel><item><title><![CDATA[AAII Sentiment Survey: Test]]></title>
                        <pubDate>Sat, 08 Aug 2026 00:00:00 GMT</pubDate>
                        <content:encoded><![CDATA[
                          Bullish sentiment increased to 40.5%.
                          Bearish sentiment decreased to 29.5%.
                        ]]></content:encoded></item></channel></rss>
                        """, MediaType.APPLICATION_XML));
    }
}
