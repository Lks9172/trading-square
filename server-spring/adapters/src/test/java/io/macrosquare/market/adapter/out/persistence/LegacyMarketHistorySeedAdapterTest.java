package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.domain.observation.MarketDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyMarketHistorySeedAdapterTest {

    @TempDir Path temp;

    @Test
    void readsKnownSeriesBoundedDeduplicatedAndSorted() throws Exception {
        Files.writeString(temp.resolve("fred-dgs10.json"), """
                [{"date":"2026-07-20","value":4.3},{"date":"2026-07-19","value":4.2},
                 {"date":"2026-07-20","value":4.4}]
                """);
        Files.writeString(temp.resolve("fred-unknown.json"), "[]");
        var adapter = new LegacyMarketHistorySeedAdapter(
                new ObjectMapper(), temp, 1024, 10,
                Map.of(MarketDataSource.FRED, Map.of("DGS10", "DGS10")));

        var series = adapter.listAvailableSeries();
        var points = adapter.load(series.getFirst());

        assertEquals(1, series.size());
        assertEquals("2026-07-19", points.get(0).observationDate().toString());
        assertEquals("2026-07-20", points.get(1).observationDate().toString());
        assertEquals(4.4, points.getLast().value());
    }
}
