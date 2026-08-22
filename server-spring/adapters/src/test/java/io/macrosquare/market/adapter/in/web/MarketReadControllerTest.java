package io.macrosquare.market.adapter.in.web;

import io.macrosquare.market.application.model.MarketReadModels;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.in.QueryMarketReadUseCase;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import io.macrosquare.market.domain.correlation.MarketCorrelationResult;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketReadControllerTest {

    @Test
    void preservesSnapshotFieldOrderNullsAndNumericPrecision() throws Exception {
        var mvc = mvc(new StubUseCase() {
            @Override
            public Document latestSnapshot() {
                return snapshot();
            }
        });

        mvc.perform(get("/api/snapshot"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(
                        "{\"timestamp\":\"2026-07-20T00:00:00.000Z\",\"raw\":{},"
                                + "\"derived\":{\"REAL_YIELD\":2.33,\"OPTIONAL\":null},"
                                + "\"regime\":{},\"signals\":[],\"allocation\":{},\"meta\":{}}"
                ));
    }

    @Test
    void preservesHistoryIdentityAndRepeatedSeriesKeyOrder() throws Exception {
        var observedKeys = new AtomicReference<List<String>>();
        var observedRange = new AtomicReference<String>();
        var mvc = mvc(new StubUseCase() {
            @Override
            public Document history(String source, String key) {
                assertEquals("yahoo", source);
                assertEquals("NASDAQ", key);
                return historyDocument();
            }

            @Override
            public Document historySeries(List<String> keys, String range, String interval) {
                observedKeys.set(keys);
                observedRange.set(range + ":" + interval);
                return seriesDocument();
            }
        });

        mvc.perform(get("/api/history/yahoo/NASDAQ"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "{\"source\":\"yahoo\",\"key\":\"NASDAQ\",\"count\":1,"
                                + "\"points\":[{\"date\":\"2026-07-17\",\"value\":25520.244140625}]}"
                ));
        mvc.perform(get("/api/history-series")
                        .param("keys", "yahoo:NASDAQ")
                        .param("keys", "signal:REGIME")
                        .param("range", "1M")
                        .param("interval", "1W"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "{\"keys\":[\"yahoo:NASDAQ\",\"signal:REGIME\"],\"range\":\"1M\","
                                + "\"interval\":\"1W\",\"series\":{}}"
                ));
        assertEquals(List.of("yahoo:NASDAQ", "signal:REGIME"), observedKeys.get());
        assertEquals("1M:1W", observedRange.get());
    }

    @Test
    void mapsUnavailableUpstreamToASanitizedBadGateway() throws Exception {
        var mvc = mvc(new StubUseCase() {
            @Override
            public Document historyCoverage() {
                throw new MarketReadUnavailableException("internal upstream detail");
            }
        });

        mvc.perform(get("/api/history/coverage"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(
                        "{\"error\":\"Legacy snapshot/history data is temporarily unavailable\"}"
                ));
    }

    private static MockMvc mvc(QueryMarketReadUseCase useCase) {
        return MockMvcBuilders.standaloneSetup(new MarketReadController(
                        useCase,
                        request -> useCase.latestSnapshot(),
                        () -> { throw new UnsupportedOperationException(); },
                        (lookback, assets) -> new MarketCorrelationResult(
                                lookback, List.of(), List.of(), List.of(), LocalDate.of(2026, 7, 20)
                        ),
                        new ObjectMapper()
                ))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Document snapshot() {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("timestamp", new TextValue("2026-07-20T00:00:00.000Z"));
        fields.put("raw", object());
        var derived = new LinkedHashMap<String, StructuredValue>();
        derived.put("REAL_YIELD", new NumberValue(new BigDecimal("2.33")));
        derived.put("OPTIONAL", MarketReadModels.NullValue.INSTANCE);
        fields.put("derived", new ObjectValue(derived));
        fields.put("regime", object());
        fields.put("signals", new ArrayValue(List.of()));
        fields.put("allocation", object());
        fields.put("meta", object());
        return MarketReadModels.document(fields);
    }

    private static Document historyDocument() {
        var point = new LinkedHashMap<String, StructuredValue>();
        point.put("date", new TextValue("2026-07-17"));
        point.put("value", new NumberValue(new BigDecimal("25520.244140625")));
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("source", new TextValue("yahoo"));
        fields.put("key", new TextValue("NASDAQ"));
        fields.put("count", new NumberValue(1L));
        fields.put("points", new ArrayValue(List.of(new ObjectValue(point))));
        return MarketReadModels.document(fields);
    }

    private static Document seriesDocument() {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("keys", new ArrayValue(List.of(new TextValue("yahoo:NASDAQ"), new TextValue("signal:REGIME"))));
        fields.put("range", new TextValue("1M"));
        fields.put("interval", new TextValue("1W"));
        fields.put("series", object());
        return MarketReadModels.document(fields);
    }

    private static ObjectValue object() {
        return new ObjectValue(new LinkedHashMap<>());
    }

    private static class StubUseCase implements QueryMarketReadUseCase {
        @Override
        public Document latestSnapshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Document historyCoverage() {
            return MarketReadModels.document(new LinkedHashMap<>());
        }

        @Override
        public Document history(String source, String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Document historySeries(List<String> keyParameters, String range, String interval) {
            throw new UnsupportedOperationException();
        }
    }
}
