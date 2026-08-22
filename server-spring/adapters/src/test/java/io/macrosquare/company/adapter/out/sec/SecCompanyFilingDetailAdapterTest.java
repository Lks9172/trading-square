package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.port.out.CompanyFilingDocumentUnavailableException;
import io.macrosquare.company.application.port.out.CompanyRevenueMixUnavailableException;
import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecCompanyFilingDetailAdapterTest {

    private static final String CIK = "0001045810";
    private static final String ACCESSION = "0001045810-26-000051";
    private static final String DIRECTORY = "/Archives/edgar/data/1045810/000104581026000051/";
    private static final String INDEX_HTML = """
            <html><body><table class="tableFile" summary="Document Format Files">
              <tr><th>Seq</th><th>Description</th><th>Document</th><th>Type</th><th>Size</th></tr>
              <tr><td>1</td><td>8-K</td><td><a href="/Archives/edgar/data/1045810/000104581026000051/nvda.htm">nvda.htm</a></td><td>8-K</td><td>26,803</td></tr>
              <tr><td>2</td><td>EX-99.1</td><td><a href="/Archives/edgar/data/1045810/000104581026000051/q1fy27pr.htm">q1fy27pr.htm</a></td><td>EX-99.1</td><td>274829</td></tr>
              <tr><td>3</td><td>Investor Presentation</td><td><a href="deck.pdf">deck.pdf</a></td><td>EX-99.2</td><td>165436</td></tr>
              <tr><td>4</td><td>Bad</td><td><a href="https://evil.test/steal.htm">steal.htm</a></td><td>EX-99.3</td><td>1</td></tr>
              <tr><td>5</td><td>Escape</td><td><a href="../other.htm">other.htm</a></td><td>EX-99.4</td><td>1</td></tr>
            </table></body></html>
            """;

    @Test
    void parsesTheAccessionDocumentTableAndRejectsLinksOutsideTheFilingDirectory() {
        var base = URI.create("https://www.sec.test");
        var index = base.resolve(DIRECTORY + ACCESSION + "-index.htm");

        var detail = SecFilingIndexParser.parse(INDEX_HTML, CIK, ACCESSION, index, base);

        assertEquals(CIK, detail.cik());
        assertEquals(3, detail.documents().size());
        assertEquals("EX-99.1", detail.documents().get(1).description());
        assertEquals(274829L, detail.documents().get(1).sizeBytes());
        assertEquals("https://www.sec.test" + DIRECTORY + "q1fy27pr.htm",
                detail.documents().get(1).sourceUrl());
        assertEquals("https://www.sec.test" + DIRECTORY + "deck.pdf",
                detail.documents().get(2).sourceUrl());
        assertFalse(detail.documents().stream().anyMatch(document -> document.sourceUrl().contains("evil")));
    }

    @Test
    void loadsAndCachesTheCorrectAccessionIndexAndNormalizesBoundedDocumentText() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var indexUrl = "https://www.sec.test" + DIRECTORY + ACCESSION + "-index.htm";
        var documentUrl = "https://www.sec.test" + DIRECTORY + "q1fy27pr.htm";
        server.expect(once(), requestTo(indexUrl))
                .andRespond(withSuccess(INDEX_HTML, MediaType.TEXT_HTML));
        server.expect(once(), requestTo(documentUrl))
                .andRespond(withSuccess("""
                        <html><style>.x{display:none}</style><script>ignore()</script><body>
                        Revenue increased 20% &amp; guidance was raised.<p>Free cash flow improved.</p>
                        </body></html>
                        """, MediaType.TEXT_HTML));
        var adapter = adapter(builder);

        var first = adapter.load("CIK-1045810", ACCESSION);
        assertSame(first, adapter.load(CIK, ACCESSION));
        var content = adapter.loadContent(documentUrl);
        assertSame(content, adapter.loadContent(documentUrl));

        assertEquals(3, first.documents().size());
        assertEquals(CompanyFilingDocumentContent.Format.HTML, content.format());
        assertTrue(content.text().contains("Revenue increased 20% & guidance was raised."));
        assertTrue(content.text().contains("Free cash flow improved."));
        assertFalse(content.text().contains("ignore"));
        assertFalse(content.text().contains("display:none"));
        server.verify();
    }

    @Test
    void extractsPdfTextPageByPageAndStopsAtTheConfiguredPageLimit() throws IOException {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var documentUrl = "https://www.sec.test" + DIRECTORY + "deck.pdf";
        server.expect(once(), requestTo(documentUrl))
                .andRespond(withSuccess(pdf(
                        "Investor Presentation revenue guidance was raised.",
                        "Second page free cash flow and capital expenditure."
                ), MediaType.APPLICATION_PDF));
        var adapter = adapter(builder, 1);

        var content = adapter.loadContent(documentUrl);

        assertEquals(CompanyFilingDocumentContent.Format.PDF, content.format());
        assertEquals(2, content.totalPages());
        assertEquals(1, content.processedPages());
        assertTrue(content.truncated());
        assertTrue(content.text().contains("Investor Presentation"));
        assertFalse(content.text().contains("Second page"));
        assertSame(content, adapter.loadContent(documentUrl));
        server.verify();
    }

    @Test
    void rejectsAFileWithAPdfExtensionButNoPdfHeader() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var documentUrl = "https://www.sec.test" + DIRECTORY + "deck.pdf";
        server.expect(once(), requestTo(documentUrl))
                .andRespond(withSuccess("not a pdf", MediaType.APPLICATION_PDF));

        assertThrows(CompanyFilingDocumentUnavailableException.class,
                () -> adapter(builder).loadContent(documentUrl));
        server.verify();
    }

    @Test
    void rejectsAnImageOnlyPdfInsteadOfTreatingBlankExtractionAsEvidence() throws IOException {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var documentUrl = "https://www.sec.test" + DIRECTORY + "deck.pdf";
        server.expect(once(), requestTo(documentUrl))
                .andRespond(withSuccess(blankPdf(), MediaType.APPLICATION_PDF));

        assertThrows(CompanyFilingDocumentUnavailableException.class,
                () -> adapter(builder).loadContent(documentUrl));
        server.verify();
    }

    @Test
    void fallsBackFromTheHtmIndexAliasToHtmlOnlyOnNotFound() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://www.sec.test" + DIRECTORY + ACCESSION + "-index.htm"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo("https://www.sec.test" + DIRECTORY + ACCESSION + "-index.html"))
                .andRespond(withSuccess(INDEX_HTML, MediaType.TEXT_HTML));

        var detail = adapter(builder).load(CIK, ACCESSION);

        assertEquals(3, detail.documents().size());
        assertTrue(detail.indexUrl().endsWith("-index.html"));
        server.verify();
    }

    @Test
    void rejectsArbitraryDocumentHostsBeforeIssuingARequest() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var adapter = adapter(builder);

        assertThrows(CompanyFilingDocumentUnavailableException.class,
                () -> adapter.loadContent("https://evil.test/Archives/edgar/data/1/file.htm"));
        assertThrows(CompanyFilingDocumentUnavailableException.class,
                () -> adapter.loadContent("https://www.sec.test/Archives/edgar/data/%2e%2e/secret.htm"));
    }

    @Test
    void loadsAndCachesBoundedInlineXbrlRevenueMixEvidence() {
        var builder = RestClient.builder().baseUrl("https://www.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var documentUrl = "https://www.sec.test" + DIRECTORY + "annual.htm";
        server.expect(once(), requestTo(documentUrl))
                .andRespond(withSuccess(inlineXbrl(), MediaType.TEXT_HTML));
        var adapter = adapter(builder);

        var first = adapter.loadRevenueMix(documentUrl);

        assertSame(first, adapter.loadRevenueMix(documentUrl));
        assertEquals(2, first.facts().size());
        assertEquals(1, first.consolidatedRevenue().size());
        assertThrows(CompanyRevenueMixUnavailableException.class,
                () -> adapter.loadRevenueMix("https://www.sec.test" + DIRECTORY + "deck.pdf"));
        server.verify();
    }

    private static SecCompanyFilingDetailAdapter adapter(RestClient.Builder builder) {
        return adapter(builder, 120);
    }

    private static SecCompanyFilingDetailAdapter adapter(RestClient.Builder builder, int maxPdfPages) {
        return new SecCompanyFilingDetailAdapter(
                builder.build(),
                URI.create("https://www.sec.test"),
                Clock.systemUTC(),
                Duration.ofHours(6),
                Duration.ofHours(24),
                Duration.ZERO,
                1_000_000,
                2_000_000,
                4_000_000,
                30_000,
                maxPdfPages,
                16,
                32,
                1
        );
    }

    private static byte[] pdf(String... pages) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (var value : pages) {
                var page = new PDPage();
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(value);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] blankPdf() throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String inlineXbrl() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance"
                      xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                      xmlns:us-gaap="http://fasb.org/us-gaap/2025"
                      xmlns:test="https://example.test/xbrl">
                  <body>
                    <xbrli:context id="total"><xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier></xbrli:entity><xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period></xbrli:context>
                    <xbrli:context id="a"><xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementBusinessSegmentsAxis">test:CloudSegmentMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity><xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period></xbrli:context>
                    <xbrli:context id="b"><xbrli:entity><xbrli:identifier scheme="test">1</xbrli:identifier><xbrli:segment><xbrldi:explicitMember dimension="us-gaap:StatementBusinessSegmentsAxis">test:ConsumerSegmentMember</xbrldi:explicitMember></xbrli:segment></xbrli:entity><xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period></xbrli:context>
                    <ix:nonFraction name="us-gaap:Revenues" contextRef="total" unitRef="USD">100</ix:nonFraction>
                    <ix:nonFraction name="us-gaap:Revenues" contextRef="a" unitRef="USD">60</ix:nonFraction>
                    <ix:nonFraction name="us-gaap:Revenues" contextRef="b" unitRef="USD">40</ix:nonFraction>
                  </body>
                </html>
                """;
    }
}
