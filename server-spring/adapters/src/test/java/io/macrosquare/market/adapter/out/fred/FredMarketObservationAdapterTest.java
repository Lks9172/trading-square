package io.macrosquare.market.adapter.out.fred;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FredMarketObservationAdapterTest {

    @Test
    void mapsASeedWindowOfNonDotObservationsWithoutLeakingProviderTypes() {
        var builder = RestClient.builder().baseUrl("https://fred.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(
                        "https://fred.test/fred/series/observations?series_id=DGS10&api_key=test-key&file_type=json&sort_order=desc&limit=10"))
                .andRespond(withSuccess("""
                        {"observations":[
                          {"date":"2026-07-20","value":"."},
                          {"date":"2026-07-19","value":"4.21"},
                          {"date":"2026-07-18","value":"4.18"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new FredMarketObservationAdapter(
                builder.build(),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                "test-key",
                Runnable::run,
                Map.of("DGS10", "DGS10")
        );

        var batch = adapter.collect();

        assertEquals(2, batch.observations().size());
        assertEquals(4.18, batch.observations().getFirst().value());
        assertEquals("2026-07-18", batch.observations().getFirst().observationDate().toString());
        assertEquals(4.21, batch.observations().getLast().value());
        server.verify();
    }

    @Test
    void excludesFutureAndImplausibleObservationsBeforePersistence() {
        var builder = RestClient.builder().baseUrl("https://fred.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(
                        "https://fred.test/fred/series/observations?series_id=DGS10&api_key=test-key&file_type=json&sort_order=desc&limit=10"))
                .andRespond(withSuccess("""
                        {"observations":[
                          {"date":"2026-07-21","value":"4.30"},
                          {"date":"2026-07-20","value":"430.0"},
                          {"date":"2026-07-19","value":"4.21"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new FredMarketObservationAdapter(
                builder.build(),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                "test-key", Runnable::run, Map.of("DGS10", "DGS10")
        );

        var batch = adapter.collect();

        assertEquals(1, batch.observations().size());
        assertEquals(4.21, batch.observations().getFirst().value());
        server.verify();
    }

    @Test
    void retriesATransientProviderFailureWithoutPublishingAFalseSourceGap() {
        var builder = RestClient.builder().baseUrl("https://fred.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var uri = "https://fred.test/fred/series/observations?series_id=DGS10&api_key=test-key"
                + "&file_type=json&sort_order=desc&limit=10";
        server.expect(once(), requestTo(uri))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(uri))
                .andRespond(withSuccess("""
                        {"observations":[{"date":"2026-07-19","value":"4.21"}]}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new FredMarketObservationAdapter(
                builder.build(),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                "test-key", Runnable::run, Map.of("DGS10", "DGS10"), Duration.ZERO
        );

        var batch = adapter.collect();

        assertEquals(1, batch.observations().size());
        assertTrue(batch.failures().isEmpty());
        server.verify();
    }

    @Test
    void doesNotRetryANonTransientProviderRejection() {
        var builder = RestClient.builder().baseUrl("https://fred.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(
                        "https://fred.test/fred/series/observations?series_id=DGS10&api_key=test-key"
                                + "&file_type=json&sort_order=desc&limit=10"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        var adapter = new FredMarketObservationAdapter(
                builder.build(),
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC),
                "test-key", Runnable::run, Map.of("DGS10", "DGS10"), Duration.ZERO
        );

        var batch = adapter.collect();

        assertTrue(batch.observations().isEmpty());
        assertEquals(1, batch.failures().size());
        assertEquals("HTTP 400", batch.failures().getFirst().reason());
        server.verify();
    }
}
