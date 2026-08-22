package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.port.out.CompanyMarketCapitalizationUnavailableException;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooCompanyMarketCapitalizationAdapterTest {

    private static final URI PRIMARY = URI.create("https://query1.finance.test");
    private static final URI SECONDARY = URI.create("https://query2.finance.test");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
    private static final String PATH = "/ws/fundamentals-timeseries/v1/finance/timeseries/KLAC"
            + "?symbol=KLAC&type=trailingMarketCap&period1=1783555200&period2=1786147200";
    private static final String CHART_PATH = "/v8/finance/chart/KLAC"
            + "?period1=1785369600&period2=1785974400&interval=1d&events=history";

    @Test
    void loadsTheLatestIndependentMarketCapAndCachesIt() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(PRIMARY + PATH)).andRespond(withSuccess("""
                {"timeseries":{"result":[{
                  "meta":{"symbol":["KLAC"],"type":["trailingMarketCap"]},
                  "trailingMarketCap":[
                    {"asOfDate":"2026-08-01","reportedValue":{"raw":250000000000}},
                    {"asOfDate":"2026-08-04","reportedValue":{"raw":255311489794}}
                  ]
                }],"error":null}}
                """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(PRIMARY + CHART_PATH)).andRespond(withSuccess("""
                {"chart":{"result":[{
                  "meta":{"symbol":"KLAC"},
                  "timestamp":[1785763800,1785850200],
                  "indicators":{"quote":[{"close":[950.0,960.0]}]}
                }],"error":null}}
                """, MediaType.APPLICATION_JSON));
        var adapter = adapter(builder);

        var first = adapter.load(" klac ");

        assertSame(first, adapter.load("KLAC"));
        assertEquals(255_311_489_794.0, first.value());
        assertEquals("2026-08-04", first.date().toString());
        assertEquals(960.0, first.referencePrice());
        server.verify();
    }

    @Test
    void fallsBackAcrossYahooHostsAndRejectsMalformedEvidence() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(PRIMARY + PATH)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(once(), requestTo(SECONDARY + PATH)).andRespond(withSuccess(
                "{\"timeseries\":{\"result\":[]}}", MediaType.APPLICATION_JSON));
        var adapter = adapter(builder);

        assertThrows(CompanyMarketCapitalizationUnavailableException.class, () -> adapter.load("KLAC"));
        server.verify();
    }

    @Test
    void rejectsAMarketCapPayloadWhoseSourceSymbolDoesNotMatchTheRequest() throws Exception {
        var root = new ObjectMapper().readTree("""
                {"timeseries":{"result":[{
                  "meta":{"symbol":["WRONG"],"type":["trailingMarketCap"]},
                  "trailingMarketCap":[
                    {"asOfDate":"2026-08-04","reportedValue":{"raw":255311489794}}
                  ]
                }],"error":null}}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> YahooCompanyMarketCapitalizationMapper.map(root, "KLAC", "KLAC"));
    }

    private static YahooCompanyMarketCapitalizationAdapter adapter(RestClient.Builder builder) {
        return new YahooCompanyMarketCapitalizationAdapter(
                builder.build(), new ObjectMapper(), List.of(PRIMARY, SECONDARY), CLOCK,
                Duration.ofMinutes(5), Duration.ofHours(1), 4);
    }
}
