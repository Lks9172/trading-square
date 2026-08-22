package io.macrosquare.research.adapter.out.sec;

import io.macrosquare.research.application.model.PeerTaxonomyUnavailableException;
import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.domain.peer.SicSectorPolicy;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SecPeerTaxonomyAdapterTest {

    @Test
    void parsesOfficialTopLevelSicForMultiSecurityIssuer() {
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertEquals(
                        "/submissions/CIK0000019617.json", request.getURI().getPath()))
                .andRespond(withSuccess("""
                        {"cik":"19617","name":"JPMORGAN CHASE & CO","sic":"6021",
                         "sicDescription":"National Commercial Banks","tickers":["JPM","AMJB"],
                         "filings":{"recent":{"accessionNumber":[]}}}
                        """, MediaType.APPLICATION_JSON));
        var adapter = adapter(builder);

        var taxonomy = adapter.collect(
                new PeerUniverseCompany("JPM", "0000019617", "JPMORGAN CHASE & CO"),
                LocalDate.parse("2026-08-05"));

        assertEquals(6021, taxonomy.sic());
        assertEquals("financials", taxonomy.sectorKey());
        server.verify();
    }

    @Test
    void treatsOfficialBlankSicAsPermanentUnavailableTaxonomy() {
        var builder = RestClient.builder().baseUrl("https://data.sec.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertEquals(
                        "/submissions/CIK0002098710.json", request.getURI().getPath()))
                .andRespond(withSuccess("""
                        {"name":"Foreign issuer","sic":"","sicDescription":""}
                        """, MediaType.APPLICATION_JSON));
        var adapter = adapter(builder);

        assertThrows(PeerTaxonomyUnavailableException.class, () -> adapter.collect(
                new PeerUniverseCompany("ADR", "0002098710", "Foreign issuer"),
                LocalDate.parse("2026-08-05")));
        server.verify();
    }

    private static SecPeerTaxonomyAdapter adapter(RestClient.Builder builder) {
        return new SecPeerTaxonomyAdapter(
                builder.build(), new ObjectMapper(), new SicSectorPolicy(), null,
                Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC),
                Duration.ZERO, 8 * 1024 * 1024L);
    }
}
