package io.macrosquare.research.adapter.out.legacy;

import io.macrosquare.market.adapter.out.json.MarketReadJsonMapper;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.research.adapter.out.market.MarketReadResearchSnapshotAdapter;
import io.macrosquare.research.application.port.out.ResearchSnapshotUnavailableException;
import io.macrosquare.research.domain.narrative.AssetSignalAction;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarketReadResearchSnapshotAdapterTest {

    @Test
    void mapsTheSharedMarketSnapshotWithoutCallingARetiredTransport() throws Exception {
        var objectMapper = new ObjectMapper();
        var document = MarketReadJsonMapper.mapSnapshot(
                objectMapper.readTree(LegacyResearchSnapshotFixture.SNAPSHOT_JSON));
        var calls = new AtomicInteger();
        var adapter = new MarketReadResearchSnapshotAdapter(port(document, calls), objectMapper);

        var snapshot = adapter.loadLatest();

        assertEquals(1, calls.get());
        assertEquals("2026-07-19T00:00:00.000Z", snapshot.timestamp());
        assertEquals(81.78, snapshot.rawValues().get("WTI"));
        assertEquals(AssetSignalAction.BUY, snapshot.assetSignals().get("NASDAQ"));
        assertEquals(46, snapshot.legacyNarratives().get(NarrativeTheme.AI_POWER).heatScore());
    }

    @Test
    void translatesMalformedPersistedDataIntoTheResearchPortFailureContract() {
        var calls = new AtomicInteger();
        var adapter = new MarketReadResearchSnapshotAdapter(
                port(MarketReadJsonMapper.mapSnapshot(minimumInvalidResearchSnapshot()), calls),
                new ObjectMapper()
        );

        assertThrows(ResearchSnapshotUnavailableException.class, adapter::loadLatest);
        assertEquals(1, calls.get());
    }

    @Test
    void excludesStaleIndicatorPointsFromResearchDecisionInputs() throws Exception {
        var objectMapper = new ObjectMapper();
        var stale = LegacyResearchSnapshotFixture.SNAPSHOT_JSON
                .replace("\"WTI\": {\"value\": 81.78}",
                        "\"WTI\": {\"value\": 81.78, \"eligibleForSignals\": false}")
                .replace("\"REAL_YIELD\": {\"value\": 2.33}",
                        "\"REAL_YIELD\": {\"value\": 2.33, \"eligibleForSignals\": false}");
        var document = MarketReadJsonMapper.mapSnapshot(objectMapper.readTree(stale));
        var adapter = new MarketReadResearchSnapshotAdapter(
                port(document, new AtomicInteger()), objectMapper);

        var snapshot = adapter.loadLatest();

        assertFalse(snapshot.rawValues().containsKey("WTI"));
        assertFalse(snapshot.derivedValues().containsKey("REAL_YIELD"));
    }

    private static tools.jackson.databind.JsonNode minimumInvalidResearchSnapshot() {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("timestamp", "2026-07-19T00:00:00.000Z");
        root.putObject("raw");
        root.putObject("derived");
        root.putObject("regime").put("regime", "NEUTRAL");
        root.putArray("signals");
        root.putObject("allocation");
        root.putObject("meta");
        return root;
    }

    private static LoadMarketReadPort port(Document document, AtomicInteger calls) {
        return new LoadMarketReadPort() {
            @Override
            public Document loadLatestSnapshot() {
                calls.incrementAndGet();
                return document;
            }

            @Override
            public Document loadHistoryCoverage() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Document loadHistory(String source, String key) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Document loadHistorySeries(List<String> keys, String range, String interval) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
