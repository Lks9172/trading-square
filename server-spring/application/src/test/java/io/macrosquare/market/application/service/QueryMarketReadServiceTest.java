package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketReadModels;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class QueryMarketReadServiceTest {

    @Test
    void delegatesFixedReadsAndNormalizesHistoryIdentity() {
        var port = new StubPort();
        var service = new QueryMarketReadService(port);

        assertSame(port.fixture, service.latestSnapshot());
        assertSame(port.fixture, service.historyCoverage());
        assertSame(port.fixture, service.history(" yahoo ", " NASDAQ "));
        assertEquals("yahoo", port.source);
        assertEquals("NASDAQ", port.key);
    }

    @Test
    void mergesRepeatedLegacyKeyParametersAndAppliesDefaults() {
        var port = new StubPort();
        var service = new QueryMarketReadService(port);

        service.historySeries(
                List.of(" yahoo:NASDAQ, signal:REGIME ", "", "fred:DGS10"),
                null,
                ""
        );

        assertEquals(List.of("yahoo:NASDAQ", "signal:REGIME", "fred:DGS10"), port.seriesKeys);
        assertEquals("1Y", port.range);
        assertEquals("1D", port.interval);
    }

    @Test
    void preservesLegacyUnknownRangeTokensForCompatibility() {
        var port = new StubPort();
        var service = new QueryMarketReadService(port);

        service.historySeries(List.of("yahoo:NASDAQ"), "BAD", "BAD");

        assertEquals("BAD", port.range);
        assertEquals("BAD", port.interval);
    }

    @Test
    void rejectsTraversalControlCharactersAndUnboundedSeriesKeys() {
        var service = new QueryMarketReadService(new StubPort());

        assertThrows(IllegalArgumentException.class, () -> service.history("..", "NASDAQ"));
        assertThrows(IllegalArgumentException.class, () -> service.history("yahoo", "../snapshot"));
        assertThrows(IllegalArgumentException.class,
                () -> service.historySeries(List.of("yahoo:NAS\nDAQ"), "1Y", "1D"));

        var tooMany = new ArrayList<String>();
        for (int index = 0; index <= QueryMarketReadService.MAX_SERIES_KEYS; index++) {
            tooMany.add("yahoo:KEY_" + index);
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.historySeries(tooMany, "1Y", "1D"));
    }

    private static final class StubPort implements LoadMarketReadPort {
        private final Document fixture = MarketReadModels.document(new LinkedHashMap<>());
        private String source;
        private String key;
        private List<String> seriesKeys = List.of();
        private String range;
        private String interval;

        @Override
        public Document loadLatestSnapshot() {
            return fixture;
        }

        @Override
        public Document loadHistoryCoverage() {
            return fixture;
        }

        @Override
        public Document loadHistory(String source, String key) {
            this.source = source;
            this.key = key;
            return fixture;
        }

        @Override
        public Document loadHistorySeries(List<String> keys, String range, String interval) {
            this.seriesKeys = keys;
            this.range = range;
            this.interval = interval;
            return fixture;
        }
    }
}
