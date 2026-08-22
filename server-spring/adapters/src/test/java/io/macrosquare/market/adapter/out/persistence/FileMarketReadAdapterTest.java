package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileMarketReadAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void readsSnapshotCoverageHistoryAndSeriesWithoutNode() throws Exception {
        var snapshot = directory.resolve("source-cache/latest-system-snapshot-default-v1.json");
        var history = directory.resolve("history");
        Files.createDirectories(snapshot.getParent());
        Files.createDirectories(history);
        Files.writeString(snapshot, """
                {"key":"latest-system-snapshot-default-v1","updatedAt":"2026-07-20T00:00:00Z","value":{
                  "timestamp":"2026-07-20T00:00:00Z","raw":{},"derived":{},"regime":{},
                  "signals":[],"allocation":{},"meta":{}}}
                """);
        Files.writeString(history.resolve("fred-dgs10.json"), """
                [{"date":"2026-07-14","value":4.1},{"date":"2026-07-15","value":4.2},
                 {"date":"2026-07-16","value":4.3},{"date":"2026-07-17","value":4.4},
                 {"date":"2026-07-20","value":4.5}]
                """);
        var adapter = adapter(snapshot, history);

        var latest = adapter.loadLatestSnapshot();
        var coverage = adapter.loadHistoryCoverage();
        var points = adapter.loadHistory("fred", "DGS10");
        var series = adapter.loadHistorySeries(List.of("fred:DGS10", "invalid"), "1W", "1W");

        assertSame(latest, adapter.loadLatestSnapshot());
        assertSame(coverage, adapter.loadHistoryCoverage());
        assertSame(points, adapter.loadHistory("fred", "DGS10"));
        assertSame(series, adapter.loadHistorySeries(List.of("fred:DGS10", "invalid"), "1W", "1W"));
        assertEquals("2026-07-20T00:00:00Z", text(latest.root(), "timestamp"));

        var fredCoverage = object(coverage.root(), "FRED-DGS10");
        assertEquals(5L, number(fredCoverage, "count"));
        assertEquals(10L, number(fredCoverage, "guaranteedYears"));
        assertEquals(5L, number(points.root(), "count"));

        var seriesObject = object(series.root(), "series");
        var dgs10 = (ArrayValue) seriesObject.fields().get("fred:DGS10");
        assertEquals(2, dgs10.values().size());
        assertEquals(null, seriesObject.fields().get("invalid"));
    }

    @Test
    void mirrorsLegacyUnknownRangeAndIntervalBehavior() throws Exception {
        var snapshot = directory.resolve("snapshot.json");
        Files.writeString(snapshot, """
                {"value":{"timestamp":"2026-07-20T00:00:00Z","raw":{},"derived":{},
                "regime":{},"signals":[],"allocation":{},"meta":{}}}
                """);
        var history = Files.createDirectory(directory.resolve("history"));
        Files.writeString(history.resolve("yahoo-nasdaq.json"), """
                [{"date":"2026-07-19","value":100},{"date":"2026-07-20","value":110}]
                """);
        var adapter = adapter(snapshot, history);

        var unknownRange = adapter.loadHistorySeries(List.of("yahoo:NASDAQ"), "BOGUS", "1D");
        var unknownInterval = adapter.loadHistorySeries(List.of("yahoo:NASDAQ"), "1W", "BOGUS");

        assertEquals(0, seriesPoints(unknownRange.root(), "yahoo:NASDAQ").values().size());
        assertEquals(1, seriesPoints(unknownInterval.root(), "yahoo:NASDAQ").values().size());
    }

    @Test
    void readsSpringOwnedObservationHistorySchema() throws Exception {
        var snapshot = directory.resolve("snapshot.json");
        Files.writeString(snapshot, """
                {"value":{"timestamp":"2026-07-20T00:00:00Z","raw":{},"derived":{},
                "regime":{},"signals":[],"allocation":{},"meta":{}}}
                """);
        var history = Files.createDirectory(directory.resolve("spring-history"));
        Files.writeString(history.resolve("yahoo-btc.json"), """
                [{"key":"BTC","providerCode":"BTC-USD","value":65000.0,
                  "observationDate":"2026-07-19","source":"YAHOO"},
                 {"key":"BTC","providerCode":"BTC-USD","value":67000.0,
                  "observationDate":"2026-07-20","source":"YAHOO"}]
                """);
        var adapter = adapter(snapshot, history);

        var coverage = adapter.loadHistoryCoverage();
        var loaded = adapter.loadHistory("yahoo", "BTC");

        assertEquals(2L, number(object(coverage.root(), "YAHOO-BTC"), "count"));
        assertEquals(2L, number(loaded.root(), "count"));
        var points = (ArrayValue) loaded.root().fields().get("points");
        assertEquals("2026-07-19", text((ObjectValue) points.values().getFirst(), "date"));
    }

    @Test
    void failsClosedForMissingOrOversizedSnapshot() throws Exception {
        var snapshot = directory.resolve("missing.json");
        var history = Files.createDirectory(directory.resolve("history"));
        var adapter = new FileMarketReadAdapter(new ObjectMapper(), CLOCK, snapshot, history, 8, 1024, 10);

        assertThrows(MarketReadUnavailableException.class, adapter::loadLatestSnapshot);
        Files.writeString(snapshot, "0123456789");
        assertThrows(MarketReadUnavailableException.class, adapter::loadLatestSnapshot);
    }

    private FileMarketReadAdapter adapter(Path snapshot, Path history) {
        return new FileMarketReadAdapter(new ObjectMapper(), CLOCK, snapshot, history, 1024 * 1024, 1024 * 1024, 100);
    }

    private static ObjectValue object(ObjectValue source, String field) {
        return (ObjectValue) source.fields().get(field);
    }

    private static String text(ObjectValue source, String field) {
        return ((TextValue) source.fields().get(field)).value();
    }

    private static long number(ObjectValue source, String field) {
        return ((NumberValue) source.fields().get(field)).value().longValue();
    }

    private static ArrayValue seriesPoints(ObjectValue root, String key) {
        return (ArrayValue) object(root, "series").fields().get(key);
    }
}
