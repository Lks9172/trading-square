package io.macrosquare.market.adapter.out.yahoo;

import io.macrosquare.market.application.port.out.CollectSectorTotalReturnHistoryPort;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YahooSectorTotalReturnHistoryAdapterTest {

    @Test
    void mapsAdjustedCloseInsteadOfRawQuoteClose() {
        var now = Instant.parse("2026-08-08T00:00:00Z");
        var period2 = now.plusSeconds(86_400).getEpochSecond();
        var period1 = period2 - 45L * 86_400L;
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://query1.test/v8/finance/chart/SPY?period1=" + period1
                        + "&period2=" + period2
                        + "&interval=1d&events=div%2Csplits&includeAdjustedClose=true"))
                .andRespond(withSuccess(response("SPY", now), MediaType.APPLICATION_JSON));
        var adapter = new YahooSectorTotalReturnHistoryAdapter(
                builder.build(), List.of(URI.create("https://query1.test")),
                Clock.fixed(now, ZoneOffset.UTC), Runnable::run, Map.of("SPY_TR", "SPY"));

        var batch = adapter.collect(CollectSectorTotalReturnHistoryPort.HistoryWindow.RECENT);

        assertTrue(batch.failures().isEmpty());
        assertEquals(5, batch.observations().size());
        assertEquals(96, batch.observations().getFirst().value());
        assertEquals("SPY:ADJCLOSE_TOTAL_RETURN", batch.observations().getFirst().providerCode());
        server.verify();
    }

    @Test
    void quarantinesAResponseWhoseSymbolDoesNotMatch() {
        var now = Instant.parse("2026-08-08T00:00:00Z");
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("/v8/finance/chart/SPY?")))
                .andRespond(withSuccess(response("QQQ", now), MediaType.APPLICATION_JSON));
        var adapter = new YahooSectorTotalReturnHistoryAdapter(
                builder.build(), List.of(URI.create("https://query1.test")),
                Clock.fixed(now, ZoneOffset.UTC), Runnable::run, Map.of("SPY_TR", "SPY"));

        var batch = adapter.collect(CollectSectorTotalReturnHistoryPort.HistoryWindow.RECENT);

        assertTrue(batch.observations().isEmpty());
        assertEquals(1, batch.failures().size());
        server.verify();
    }

    private static String response(String symbol, Instant now) {
        var timestamps = new StringBuilder();
        for (var index = 4; index >= 0; index--) {
            if (!timestamps.isEmpty()) timestamps.append(',');
            timestamps.append(now.minusSeconds(index * 86_400L).getEpochSecond());
        }
        return """
                {"chart":{"result":[{"meta":{"symbol":"%s"},"timestamp":[%s],
                "indicators":{"quote":[{"close":[100,101,102,103,104]}],
                "adjclose":[{"adjclose":[96,97,98,99,100]}]}}]}}
                """.formatted(symbol, timestamps);
    }
}
