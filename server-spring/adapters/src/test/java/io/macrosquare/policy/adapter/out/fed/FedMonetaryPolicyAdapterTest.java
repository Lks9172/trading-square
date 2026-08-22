package io.macrosquare.policy.adapter.out.fed;

import io.macrosquare.policy.domain.model.PolicyDocumentType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FedMonetaryPolicyAdapterTest {

    private static final URI FEED = URI.create("https://www.federalreserve.gov/feeds/press_monetary.xml");
    private static final String DOCUMENT_URL =
            "https://www.federalreserve.gov/newsevents/pressreleases/monetary20260701a.htm";

    @Test
    void collectsOfficialRssAndExtractsOnlyTheArticleEvidence() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(FEED.toString())).andRespond(withSuccess(rss(), MediaType.APPLICATION_XML));
        server.expect(once(), requestTo(DOCUMENT_URL)).andRespond(withSuccess("""
                <html><body><div id="article">
                  <script>ignore me</script><h1>Federal Reserve issues FOMC statement</h1>
                  <p>Inflation has eased &amp; labor market conditions have cooled.</p>
                </div><div id="lastUpdate">July 1, 2026</div></body></html>
                """, MediaType.TEXT_HTML));
        var adapter = adapter(builder.build());

        var documents = adapter.collect(3);

        assertEquals(1, documents.size());
        assertEquals(PolicyDocumentType.FOMC_STATEMENT, documents.getFirst().type());
        assertTrue(documents.getFirst().text().contains("Inflation has eased & labor"));
        assertTrue(!documents.getFirst().text().contains("ignore me"));
        server.verify();
    }

    @Test
    void rejectsNonOfficialFeedHostsBeforeAnyRequest() {
        assertThrows(IllegalArgumentException.class, () -> new FedMonetaryPolicyAdapter(
                RestClient.create(), URI.create("https://example.com/feed.xml"), null,
                Clock.systemUTC(), Duration.ZERO, 1_000, 1_000));
    }

    private static FedMonetaryPolicyAdapter adapter(RestClient client) {
        return new FedMonetaryPolicyAdapter(
                client, FEED, null,
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                Duration.ZERO, 100_000, 100_000);
    }

    private static String rss() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><item>
                  <title>Federal Reserve issues FOMC statement</title>
                  <link>https://www.federalreserve.gov/newsevents/pressreleases/monetary20260701a.htm</link>
                  <description>Official statement</description>
                  <pubDate>Wed, 01 Jul 2026 18:00:00 GMT</pubDate>
                </item></channel></rss>
                """;
    }
}
