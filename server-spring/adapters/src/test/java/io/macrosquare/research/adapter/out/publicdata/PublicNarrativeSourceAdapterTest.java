package io.macrosquare.research.adapter.out.publicdata;

import io.macrosquare.research.application.service.NarrativeThemeCatalog;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PublicNarrativeSourceAdapterTest {

    @Test
    void collectsAuditableKeylessNewsAndWikimediaSignalsAndMarksYoutubeMissing() {
        var googleBuilder = RestClient.builder().baseUrl("https://news.google.test");
        var googleServer = MockRestServiceServer.bindTo(googleBuilder).build();
        googleServer.expect(request -> assertEquals("/rss/search", request.getURI().getPath()))
                .andRespond(withSuccess(newsFeed(), MediaType.APPLICATION_XML));
        googleServer.expect(request -> assertEquals("/rss/search", request.getURI().getPath()))
                .andRespond(withSuccess(newsFeed(), MediaType.APPLICATION_XML));

        var wikimediaBuilder = RestClient.builder().baseUrl("https://wikimedia.test");
        var wikimediaServer = MockRestServiceServer.bindTo(wikimediaBuilder).build();
        wikimediaServer.expect(request -> assertTrue(request.getURI().getPath().contains("Artificial_intelligence")))
                .andRespond(withSuccess(wikimediaBody(), MediaType.APPLICATION_JSON));
        wikimediaServer.expect(request -> assertTrue(
                        request.getURI().getPath().contains("Fast-moving_consumer_goods")))
                .andRespond(withSuccess(wikimediaBody(), MediaType.APPLICATION_JSON));

        var youtubeBuilder = RestClient.builder().baseUrl("https://youtube.test");
        var youtubeServer = MockRestServiceServer.bindTo(youtubeBuilder).build();
        var clock = Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC);
        var adapter = new PublicNarrativeSourceAdapter(
                googleBuilder.build(), wikimediaBuilder.build(), youtubeBuilder.build(), new ObjectMapper(),
                null, clock, "", Duration.ZERO, 2_000_000);

        var catalog = new NarrativeThemeCatalog();
        var readings = adapter.collect(java.util.List.of(
                catalog.definition(NarrativeTheme.AI_POWER),
                catalog.definition(NarrativeTheme.CONSUMER_DEFENSIVE)));

        assertEquals(6, readings.size());
        assertEquals(4, readings.stream().filter(value -> value.status() == NarrativeSourceStatus.AVAILABLE).count());
        assertEquals(2, readings.stream()
                .filter(value -> value.sourceKey().equals("YOUTUBE_30D"))
                .filter(value -> value.status() == NarrativeSourceStatus.MISSING)
                .count());
        assertEquals(2d, readings.stream().filter(value -> value.sourceKey().equals("GOOGLE_NEWS_7D"))
                .findFirst().orElseThrow().value());
        googleServer.verify();
        wikimediaServer.verify();
        youtubeServer.verify();
    }

    private static String newsFeed() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item><title>one</title><pubDate>Mon, 20 Jul 2026 12:00:00 GMT</pubDate></item>
                  <item><title>two</title><pubDate>Sat, 18 Jul 2026 12:00:00 GMT</pubDate></item>
                  <item><title>old</title><pubDate>Mon, 01 Jun 2026 12:00:00 GMT</pubDate></item>
                </channel></rss>
                """;
    }

    private static String wikimediaBody() {
        var start = LocalDate.parse("2026-06-14");
        var values = new StringBuilder("{\"items\":[");
        for (var index = 0; index < 36; index++) {
            if (index > 0) values.append(',');
            var views = index < 29 ? 100 : 150;
            values.append("{\"timestamp\":\"")
                    .append(start.plusDays(index).toString().replace("-", ""))
                    .append("00\",\"views\":").append(views).append('}');
        }
        return values.append("]}").toString();
    }
}
