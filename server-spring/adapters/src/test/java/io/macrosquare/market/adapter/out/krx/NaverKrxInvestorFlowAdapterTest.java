package io.macrosquare.market.adapter.out.krx;

import io.macrosquare.market.domain.observation.MarketDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NaverKrxInvestorFlowAdapterTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-20T16:00:00Z"), ZoneOffset.UTC);

    @Test
    void readsSixEucKrPagesIntoARealSixtyDayInvestorHistory() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        expectValidPages(server, 1, 6);
        var adapter = new NaverKrxInvestorFlowAdapter(
                builder.build(), URI.create("https://finance.test/investor"), CLOCK);

        var batch = adapter.collect();

        assertTrue(batch.failures().isEmpty());
        assertEquals(600, batch.observations().size());
        var latestForeign = batch.observations().stream()
                .filter(item -> item.key().equals("KOSPI_FOREIGN_NET_1D"))
                .filter(item -> item.observationDate().toString().equals("2026-07-21"))
                .findFirst().orElseThrow();
        assertEquals(2_952d, latestForeign.value());
        assertEquals("NAVER_FINANCE:KOSPI:FOREIGN", latestForeign.providerCode());
        assertEquals(MarketDataSource.KRX, latestForeign.source());
        server.verify();
    }

    @Test
    void preservesValidLatestRowsAndReportsTheFailedHistoryPage() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(
                        "https://finance.test/investor?bizdate=20260721&sosok=01&page=1"))
                .andRespond(withSuccess(page(1), MediaType.TEXT_HTML));
        server.expect(once(), requestTo(
                        "https://finance.test/investor?bizdate=20260721&sosok=01&page=2"))
                .andRespond(withSuccess("<html>schema changed</html>".getBytes(EUC_KR), MediaType.TEXT_HTML));
        expectValidPages(server, 3, 6);
        var adapter = new NaverKrxInvestorFlowAdapter(
                builder.build(), URI.create("https://finance.test/investor"), CLOCK);

        var batch = adapter.collect();

        assertEquals(500, batch.observations().size());
        assertEquals(1, batch.failures().size());
        assertEquals("KOSPI_INVESTOR_FLOW_PAGE_2", batch.failures().getFirst().key());
        assertEquals("Malformed provider response", batch.failures().getFirst().reason());
        server.verify();
    }

    private static void expectValidPages(MockRestServiceServer server, int firstPage, int lastPage) {
        for (var page = firstPage; page <= lastPage; page++) {
            server.expect(once(), requestTo(
                            "https://finance.test/investor?bizdate=20260721&sosok=01&page=" + page))
                    .andRespond(withSuccess(page(page), MediaType.TEXT_HTML));
        }
    }

    private static byte[] page(int page) {
        var html = new StringBuilder("<html><body><table>");
        var firstDate = LocalDate.of(2026, 7, 21).minusDays((long) (page - 1) * 10);
        for (var offset = 0; offset < 10; offset++) {
            var date = firstDate.minusDays(offset);
            var foreign = page == 1 && offset == 0 ? "2,952" : "-1,000";
            html.append("<tr>\n")
                    .append("<td class=\"date2\">")
                    .append(date.format(DateTimeFormatter.ofPattern("yy.MM.dd")))
                    .append("</td>")
                    .append("<td class=\"rate_down3\">-16,421</td>")
                    .append("<td class=\"rate_up3\">").append(foreign).append("</td>")
                    .append("<td>13,744</td><td>8,891</td><td>1,157</td>")
                    .append("<td>3,158</td><td>4</td><td>-66</td><td>600</td><td>-275</td></tr>");
        }
        return html.append("</table></body></html>").toString().getBytes(EUC_KR);
    }
}
