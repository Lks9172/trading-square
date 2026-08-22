package io.macrosquare.market.adapter.out.sentiment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpStatus;

class FearGreedMarketObservationAdapterTest {

    @Test
    void mapsCnnScoreAndDoesNotCallFallbackWhenPrimaryIsUsable() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://cnn.test/fng"))
                .andRespond(withSuccess("""
                        {"fear_and_greed":{"score":68.7,"timestamp":"2026-07-20T12:00:00Z"}}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new FearGreedMarketObservationAdapter(
                builder.build(),
                URI.create("https://cnn.test/fng"),
                URI.create("https://alternative.test/fng"),
                Clock.fixed(Instant.parse("2026-07-20T13:00:00Z"), ZoneOffset.UTC)
        );

        var batch = adapter.collect();

        assertEquals(1, batch.observations().size());
        assertEquals(69, batch.observations().getFirst().value());
        assertEquals("2026-07-20", batch.observations().getFirst().observationDate().toString());
        server.verify();
    }

    @Test
    void neverSubstitutesCryptoSentimentForTheCnnStockMarketIndex() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://cnn.test/fng"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo("https://alternative.test/fng"))
                .andRespond(withSuccess("""
                        {"data":[{"value":"25","timestamp":"1784505600"}]}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new FearGreedMarketObservationAdapter(
                builder.build(), URI.create("https://cnn.test/fng"),
                URI.create("https://alternative.test/fng"),
                Clock.fixed(Instant.parse("2026-07-20T13:00:00Z"), ZoneOffset.UTC)
        );

        var batch = adapter.collect();

        assertEquals("CRYPTO_FEAR_GREED", batch.observations().getFirst().key());
        assertEquals(1, batch.failures().size());
        server.verify();
    }
}
