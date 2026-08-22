package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySourceCacheCompanyAnalystHistoryAdapterTest {

    @TempDir
    Path directory;

    @Test
    void mapsTickerHistoryWithoutWriting() throws IOException {
        writeHistory("nvda", """
                [
                  {"date":"2026-06-19","analystScore":1.097,"upsidePct":41.88},
                  {"date":"2026-07-19","analystScore":1.098,"upsidePct":49.06}
                ]
                """);
        var path = directory.resolve(historyFile("nvda"));
        var before = Files.getLastModifiedTime(path);

        var history = adapter().load(" nvda ");

        assertEquals(2, history.size());
        assertEquals(41.88, history.getFirst().upsidePct());
        assertEquals(before, Files.getLastModifiedTime(path));
    }

    @Test
    void acceptsAMissingTickerHistoryAsEmpty() {
        assertTrue(adapter().load("NEM").isEmpty());
    }

    @Test
    void failsClosedForMalformedHistory() throws IOException {
        writeHistory("nvda", "[{\"date\":\"not-a-date\",\"analystScore\":1,\"upsidePct\":2}]");
        assertThrows(CompanyAnalystEvidenceUnavailableException.class, () -> adapter().load("NVDA"));
    }

    @Test
    void preventsTickerBasedPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> adapter().load("../../secret"));
    }

    private LegacySourceCacheCompanyAnalystHistoryAdapter adapter() {
        return new LegacySourceCacheCompanyAnalystHistoryAdapter(new ObjectMapper(), directory);
    }

    private void writeHistory(String ticker, String valueJson) throws IOException {
        Files.writeString(directory.resolve(historyFile(ticker)), """
                {"key":"company-analyst-history-%s","updatedAt":"2026-07-19T13:33:41Z","value":%s}
                """.formatted(ticker, valueJson));
    }

    private static String historyFile(String ticker) {
        return "company-analyst-history-" + ticker + ".json";
    }
}
