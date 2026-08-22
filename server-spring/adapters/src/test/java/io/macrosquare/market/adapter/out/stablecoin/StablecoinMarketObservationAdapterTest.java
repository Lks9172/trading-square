package io.macrosquare.market.adapter.out.stablecoin;

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

class StablecoinMarketObservationAdapterTest {

    @Test
    void sumsPositiveUsdPeggedCirculationInBillions() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://stablecoin.test/list"))
                .andRespond(withSuccess("""
                        {"peggedAssets":[
                          {"circulating":{"peggedUSD":100000000000}},
                          {"circulating":{"peggedUSD":50550000000}},
                          {"circulating":{"peggedUSD":-1}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        var adapter = new StablecoinMarketObservationAdapter(
                builder.build(), URI.create("https://stablecoin.test/list"),
                Clock.fixed(Instant.parse("2026-07-20T13:00:00Z"), ZoneOffset.UTC));

        var batch = adapter.collect();

        assertEquals(1, batch.observations().size());
        assertEquals(150.55, batch.observations().getFirst().value());
        server.verify();
    }
}
