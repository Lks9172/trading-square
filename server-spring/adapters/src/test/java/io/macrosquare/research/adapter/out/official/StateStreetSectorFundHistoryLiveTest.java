package io.macrosquare.research.adapter.out.official;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in issuer contract probe; excluded from ordinary offline builds. */
@EnabledIfEnvironmentVariable(named = "MACROSQUARE_LIVE_SOURCE_TESTS", matches = "true")
class StateStreetSectorFundHistoryLiveTest {

    @ParameterizedTest
    @ValueSource(strings = {"XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB", "XLRE", "XLU", "XLP"})
    void canonicalWorkbookEndpointStillExposesDatedNavAndSharesHistory(String ticker) {
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        var adapter = new StateStreetSectorFundHistoryAdapter(
                RestClient.builder().requestFactory(requestFactory)
                        .defaultHeader("User-Agent", "MacroSquare-source-contract/1.0")
                        .build(),
                URI.create("https://www.ssga.com/library-content/products/fund-data/etfs/us/"),
                2L * 1024 * 1024);

        var points = adapter.load(ticker);

        assertTrue(points.size() >= 21);
        assertEquals(points.size(), points.stream().map(point -> point.observedOn()).distinct().count());
        assertTrue(points.getLast().nav() > 0);
        assertTrue(points.getLast().sharesOutstanding() > 0);
    }
}
