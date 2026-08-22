package io.macrosquare.compatibility.adapter.out.earnings;

import io.macrosquare.compatibility.adapter.out.json.SupplementalApiJsonMapper;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.port.out.LoadSupplementalApiPort;
import io.macrosquare.compatibility.application.port.out.LoadEarningsUniversePort;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NasdaqEarningsSupplementalApiAdapterTest {

    @Test
    void treatsASuccessfulEmptyCurrentWindowAsFreshInsteadOfResurrectingStaleDates() {
        var mapper = new ObjectMapper();
        var fallback = mock(LoadSupplementalApiPort.class);
        when(fallback.loadEarnings()).thenReturn(staleCalendar(mapper));
        var builder = RestClient.builder().baseUrl("https://nasdaq.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var today = LocalDate.parse("2026-08-06");
        for (var offset = 0; offset < 5; offset++) {
            server.expect(once(), requestTo(
                            "https://nasdaq.test/api/calendar/earnings?date=" + today.plusDays(offset)))
                    .andRespond(withSuccess("{\"data\":{\"rows\":[]}}", MediaType.APPLICATION_JSON));
        }
        var adapter = new NasdaqEarningsSupplementalApiAdapter(
                fallback,
                () -> java.util.Set.of("SCHW"),
                builder.build(),
                mapper,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(30),
                Runnable::run
        );

        var result = adapter.loadEarnings();

        var outbound = (java.util.Map<?, ?>) SupplementalApiJsonMapper.outbound(result);
        assertEquals(0L, outbound.get("count"));
        assertEquals(java.util.List.of(), outbound.get("earnings"));
        server.verify();
    }

    @Test
    void includesAnyCurrentResearchCompanyInsteadOfOnlyAHardCodedMegaCapSubset() {
        var mapper = new ObjectMapper();
        var fallback = mock(LoadSupplementalApiPort.class);
        when(fallback.loadEarnings()).thenReturn(staleCalendar(mapper));
        LoadEarningsUniversePort universe = () -> java.util.Set.of("COP");
        var builder = RestClient.builder().baseUrl("https://nasdaq.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var today = LocalDate.parse("2026-08-06");
        for (var offset = 0; offset < 5; offset++) {
            var body = offset == 0
                    ? "{\"data\":{\"rows\":[{\"symbol\":\"COP\",\"name\":\"ConocoPhillips\",\"time\":\"time-pre-market\"},{\"symbol\":\"OUT\",\"name\":\"Outside\",\"time\":\"TBD\"}]}}"
                    : "{\"data\":{\"rows\":[]}}";
            server.expect(once(), requestTo(
                            "https://nasdaq.test/api/calendar/earnings?date=" + today.plusDays(offset)))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        var adapter = new NasdaqEarningsSupplementalApiAdapter(
                fallback, universe, builder.build(), mapper,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(30), Runnable::run);

        var outbound = (java.util.Map<?, ?>) SupplementalApiJsonMapper.outbound(adapter.loadEarnings());

        assertEquals(1L, outbound.get("count"));
        var events = (java.util.List<?>) outbound.get("earnings");
        assertEquals("COP", ((java.util.Map<?, ?>) events.getFirst()).get("ticker"));
        server.verify();
    }

    private static Document staleCalendar(ObjectMapper mapper) {
        var root = mapper.createObjectNode();
        var item = root.putArray("earnings").addObject();
        item.put("ticker", "SCHW");
        item.put("company", "The Charles Schwab Corporation");
        item.put("date", "2026-07-21");
        item.put("time", "time-pre-market");
        root.put("count", 1);
        return SupplementalApiJsonMapper.document(root, SupplementalApiJsonMapper.Contract.EARNINGS);
    }
}
