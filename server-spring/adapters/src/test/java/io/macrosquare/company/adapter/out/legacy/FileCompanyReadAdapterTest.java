package io.macrosquare.company.adapter.out.legacy;

import io.macrosquare.company.adapter.out.persistence.FileCompanyReadAdapter;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.port.in.CompanyTickerNotFoundException;
import io.macrosquare.company.application.port.out.CompanyReadUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileCompanyReadAdapterTest {

    @TempDir
    Path directory;

    @Test
    void searchesThePersistedSecDirectoryWithLegacyRanking() throws Exception {
        writeDirectory();
        var adapter = adapter();

        var exact = adapter.search("NVDA", 5);
        var title = adapter.search("NVIDIA", 5);

        assertEquals(List.of("NVDA", "NVDAA"), exact.items().stream().map(item -> item.ticker()).toList());
        assertEquals("0001045810", title.items().getFirst().cik());
    }

    @Test
    void readsLiteSummariesAndUsesTheLastValidLiteProjectionForDetail() throws Exception {
        writeDirectory();
        writeEnvelope("company-research-lite-nvda.json", researchWithSummaryFields());
        var adapter = adapter();

        var summary = adapter.summaries(List.of("NVDA")).items().getFirst();
        assertEquals(64, summary.totalScore());
        assertEquals(55, summary.volumeConfirmationScore());
        var liteDetail = adapter.detail("NVDA");
        assertEquals("NVDA", ((TextValue) liteDetail.profile().fields().get("ticker")).value());

        writeEnvelope("route_company-detail_v1_nvda.json", LegacyCompanyProjectionFixture.RESEARCH_JSON);
        var detail = adapter.detail("NVDA");
        assertEquals("NVDA", ((TextValue) detail.profile().fields().get("ticker")).value());
    }

    @Test
    void distinguishesUnknownTickersFromMissingPersistedDetails() throws Exception {
        writeDirectory();
        var adapter = adapter();

        assertThrows(CompanyTickerNotFoundException.class, () -> adapter.detail("NOTREAL"));
        assertThrows(CompanyReadUnavailableException.class, () -> adapter.detail("NVDA"));
    }

    @Test
    void appliesCurrentLifecycleRulesInTheFileFallbackToo() throws Exception {
        Files.writeString(directory.resolve("sec-company-ticker-map.json"), """
                {"key":"sec-company-ticker-map","updatedAt":"2026-07-20T00:00:00Z","value":{
                  "EA":{"ticker":"EA","cik":"0000712515","title":"Electronic Arts"},
                  "MMC":{"ticker":"MMC","cik":"0000062709","title":"Marsh legacy"}
                }}
                """);
        writeEnvelope("company-research-lite-mmc.json", LegacyCompanyProjectionFixture.RESEARCH_JSON
                .replace("\"ticker\":\"NVDA\"", "\"ticker\":\"MMC\""));
        var adapter = adapter();

        assertEquals(List.of(), adapter.search("ELECTRONIC", 5).items());
        assertEquals(List.of(), adapter.summaries(List.of("EA")).items());
        assertThrows(CompanyTickerNotFoundException.class, () -> adapter.detail("EA"));
        assertEquals("MRSH", adapter.search("MARSH", 5).items().getFirst().ticker());
        assertEquals("MRSH", ((TextValue) adapter.detail("MRSH").profile().fields().get("ticker")).value());
    }

    private FileCompanyReadAdapter adapter() {
        return new FileCompanyReadAdapter(new ObjectMapper(), directory.toAbsolutePath(), 1024 * 1024, 16);
    }

    private void writeDirectory() throws Exception {
        Files.writeString(directory.resolve("sec-company-ticker-map.json"), """
                {"key":"sec-company-ticker-map","updatedAt":"2026-07-20T00:00:00Z","value":{
                  "AAPL":{"ticker":"AAPL","cik":"0000320193","title":"Apple Inc."},
                  "NVDA":{"ticker":"NVDA","cik":"0001045810","title":"NVIDIA CORP"},
                  "NVDAA":{"ticker":"NVDAA","cik":"0000000001","title":"NVIDIA TEST"}
                }}
                """);
    }

    private void writeEnvelope(String fileName, String value) throws Exception {
        Files.writeString(directory.resolve(fileName), """
                {"key":"test","updatedAt":"2026-07-20T00:00:00Z","value":%s}
                """.formatted(value));
    }

    private static String researchWithSummaryFields() {
        return LegacyCompanyProjectionFixture.RESEARCH_JSON
                .replace("\"financials\":{}", "\"financials\":{" +
                        "\"revenueGrowthYoY\":61.4,\"operatingMargin\":null,\"evToSales\":null}")
                .replace("\"score\":{}", "\"score\":{\"totalScore\":64}")
                .replace("\"buyScore\":{}", "\"buyScore\":{" +
                        "\"buyScore\":64,\"label\":\"선별 접근\",\"crowdingScore\":42,\"appealScore\":67}")
                .replace("\"bottomSignal\":{}", "\"bottomSignal\":{" +
                        "\"state\":\"바닥 시도\",\"earningsBottomScore\":64,\"priceBottomScore\":52," +
                        "\"volumeConfirmationScore\":55,\"failureRiskScore\":52}");
    }
}
