package io.macrosquare.compatibility.adapter.out.persistence;

import io.macrosquare.compatibility.adapter.out.json.SupplementalApiJsonMapper;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileSupplementalApiAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesOnlyCanonicalCurrentUniverseCompanies() throws Exception {
        var adapter = adapter(catalog(
                item("CTRA", "Coterra Energy"),
                item("EA", "Electronic Arts"),
                item("MMC", "Marsh & McLennan"),
                item("MRSH", "Marsh McLennan"),
                item("NVDA", "NVIDIA Corporation")
        ));

        var outbound = outbound(adapter.loadCompanies("buy", "", "", "", 1, 100));
        var items = items(outbound);

        assertEquals(4L, outbound.get("total"));
        assertEquals(List.of("EPD", "MRSH", "NVDA", "RBLX"), items.stream()
                .map(value -> String.valueOf(value.get("ticker"))).sorted().toList());
        var replacements = items.stream()
                .filter(value -> Set.of("EPD", "RBLX").contains(String.valueOf(value.get("ticker"))))
                .toList();
        assertEquals(List.of(false, false), replacements.stream()
                .map(value -> value.containsKey("totalScore")).toList());
        assertEquals(List.of("communication-services", "energy"), replacements.stream()
                .map(value -> String.valueOf(((List<?>) value.get("sectorIds")).getFirst()))
                .sorted().toList());
    }

    @Test
    void companySearchMatchesCompanyNameAsWellAsTickerAndClassification() throws Exception {
        var adapter = adapter(catalog(
                item("MRSH", "Marsh McLennan"),
                item("NVDA", "NVIDIA Corporation")
        ));

        var outbound = outbound(adapter.loadCompanies("buy", "NVIDIA", "", "", 1, 100));

        assertEquals(1L, outbound.get("total"));
        assertEquals("NVDA", items(outbound).getFirst().get("ticker"));
    }

    @Test
    void marksOldSmartMoneyAsVisibleButIneligibleForDecisions() {
        var root = objectMapper.createObjectNode();
        var insider = root.putObject("insider");
        insider.put("lastUpdated", "2026-07-20");
        insider.put("score", -2);
        JsonEnvelopeStore store = fileName -> Optional.of(root);
        var adapter = new FileSupplementalApiAdapter(
                store, objectMapper, null,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC));

        var outbound = outbound(adapter.loadSmartMoney());
        @SuppressWarnings("unchecked")
        var freshness = (Map<String, Object>) outbound.get("freshness");

        assertEquals("STALE", freshness.get("status"));
        assertEquals(false, freshness.get("eligibleForDecisions"));
        assertEquals(17L, freshness.get("ageDays"));
    }

    @Test
    void recalculatesCapturedDomesticReportAgeAndMarksItDisplayOnly() {
        var root = objectMapper.createObjectNode();
        var data = root.putObject("data");
        data.put("fetchedAt", "2026-07-20T05:00:53.737Z");
        var market = data.putObject("marketInfo");
        market.put("latestDate", "2026-07-20");
        market.put("daysAgo", 0);
        market.put("title", "captured report");
        JsonEnvelopeStore store = fileName -> Optional.of(root);
        var adapter = new FileSupplementalApiAdapter(
                store, objectMapper, null,
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC));

        var outbound = outbound(adapter.loadDomesticReports());
        @SuppressWarnings("unchecked")
        var outboundData = (Map<String, Object>) outbound.get("data");
        @SuppressWarnings("unchecked")
        var report = (Map<String, Object>) outboundData.get("marketInfo");
        @SuppressWarnings("unchecked")
        var freshness = (Map<String, Object>) outboundData.get("freshness");

        assertEquals(17L, report.get("daysAgo"));
        assertEquals("STALE", freshness.get("status"));
        assertEquals(false, freshness.get("usedForInvestmentScores"));
        assertEquals(false, freshness.get("eligibleForDecisions"));
    }

    private FileSupplementalApiAdapter adapter(tools.jackson.databind.JsonNode catalog) {
        JsonEnvelopeStore store = fileName -> Optional.of(catalog);
        return new FileSupplementalApiAdapter(store, objectMapper);
    }

    private tools.jackson.databind.JsonNode catalog(tools.jackson.databind.JsonNode... values) {
        var root = objectMapper.createObjectNode();
        var items = root.putArray("items");
        for (var value : values) items.add(value);
        root.putArray("themes");
        root.putArray("sectors");
        return root;
    }

    private tools.jackson.databind.JsonNode item(String ticker, String name) {
        var item = objectMapper.createObjectNode();
        item.put("ticker", ticker);
        item.put("name", name);
        item.putArray("themeIds").add("ai");
        item.putArray("themeNames").add("AI");
        item.putArray("sectorIds").add("technology");
        item.putArray("sectorNames").add("Technology");
        return item;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outbound(io.macrosquare.compatibility.application.model.SupplementalApiModels.Document document) {
        return (Map<String, Object>) SupplementalApiJsonMapper.outbound(document);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> outbound) {
        return (List<Map<String, Object>>) (List<?>) outbound.get("items");
    }
}
