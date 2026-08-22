package io.macrosquare.policy.adapter.out.official;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OfficialAgencyPolicyAdapterTest {

    @Test
    void boundsStableDocumentIdsAndExtractsOnlyRelevantOfficialArticles() {
        var listing = URI.create("https://home.treasury.gov/news/press-releases");
        var slug = "a".repeat(180);
        var document = "https://home.treasury.gov/news/press-releases/" + slug;
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(listing.toString())).andRespond(withSuccess("""
                <time datetime="2026-07-20T12:00:00Z"></time>
                <a href="/news/press-releases/%s">Treasury announces market liquidity support</a>
                """.formatted(slug), MediaType.TEXT_HTML));
        server.expect(once(), requestTo(document)).andRespond(withSuccess("""
                <main><script>ignore</script><h1>Announcement</h1><p>Market liquidity support continues.</p></main>
                """, MediaType.TEXT_HTML));
        var adapter = new OfficialAgencyPolicyAdapter(
                builder.build(), listing, "home.treasury.gov", "U.S. Treasury", "treasury",
                PolicyDocumentType.TREASURY_RELEASE, "/news/press-releases/", Set.of("liquidity"),
                5, null, Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                Duration.ZERO, 100_000, 100_000);

        var result = adapter.collect(5);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().id().length() <= 128);
        assertTrue(result.getFirst().text().contains("liquidity support"));
        assertTrue(!result.getFirst().text().contains("ignore"));
        server.verify();
    }
}
