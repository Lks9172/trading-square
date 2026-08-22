package io.macrosquare.company.adapter.out.legacy;

import io.macrosquare.company.adapter.out.persistence.ObjectCompanyReadAdapter;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.shared.adapter.out.persistence.ReadOnlyJsonEnvelopeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectCompanyReadAdapterTest {

    @TempDir
    Path directory;

    @Test
    void servesTheCurrentMrshSymbolFromTheImmutableMmcCutoverArtifact() throws Exception {
        var mapper = new ObjectMapper();
        write("sec-company-ticker-map.json", """
                {
                  "MMC":{"ticker":"MMC","cik":"0000062709","title":"Marsh legacy"},
                  "MRSH":{"ticker":"MRSH","cik":"0000062709","title":"Marsh"}
                }
                """);
        write("company-research-lite-mmc.json", LegacyCompanyProjectionFixture.RESEARCH_JSON
                .replace("\"ticker\":\"NVDA\"", "\"ticker\":\"MMC\""));
        var adapter = new ObjectCompanyReadAdapter(new ReadOnlyJsonEnvelopeStore(
                mapper, directory.toAbsolutePath(), 1024 * 1024, 16));

        var current = adapter.detail("MRSH");
        var legacyRoute = adapter.detail("MMC");

        assertEquals("MRSH", ((TextValue) current.profile().fields().get("ticker")).value());
        assertEquals("MRSH", ((TextValue) current.financials().fields().get("ticker")).value());
        assertEquals("MRSH", ((TextValue) current.score().fields().get("ticker")).value());
        assertEquals("MRSH", ((TextValue) current.quote().fields().get("symbol")).value());
        assertEquals("MRSH", ((TextValue) legacyRoute.profile().fields().get("ticker")).value());
        assertEquals("MRSH", adapter.summaries(java.util.List.of("MMC")).items().getFirst().ticker());
        assertTrue(adapter.search("MMC", 12).items().isEmpty());
        assertEquals("MRSH", adapter.search("MARSH", 12).items().getFirst().ticker());
    }

    @Test
    void excludesRetiredCompaniesFromEveryCurrentReadBoundary() throws Exception {
        var mapper = new ObjectMapper();
        write("sec-company-ticker-map.json", """
                {
                  "EA":{"ticker":"EA","cik":"0000712515","title":"Electronic Arts"},
                  "NVDA":{"ticker":"NVDA","cik":"0001045810","title":"NVIDIA"}
                }
                """);
        write("company-research-lite-ea.json", LegacyCompanyProjectionFixture.RESEARCH_JSON
                .replace("\"ticker\":\"NVDA\"", "\"ticker\":\"EA\""));
        var adapter = new ObjectCompanyReadAdapter(new ReadOnlyJsonEnvelopeStore(
                mapper, directory.toAbsolutePath(), 1024 * 1024, 16));

        assertTrue(adapter.search("ELECTRONIC", 12).items().isEmpty());
        assertTrue(adapter.summaries(java.util.List.of("EA")).items().isEmpty());
        assertThrows(io.macrosquare.company.application.port.in.CompanyTickerNotFoundException.class,
                () -> adapter.detail("EA"));
    }

    @Test
    void createsAnIdentityOnlyFailClosedSeedForANewCurrentReplacement() throws Exception {
        var mapper = new ObjectMapper();
        write("sec-company-ticker-map.json", """
                {
                  "RBLX":{"ticker":"RBLX","cik":"0001315098","title":"Roblox Corporation"},
                  "NVDA":{"ticker":"NVDA","cik":"0001045810","title":"NVIDIA"}
                }
                """);
        var adapter = new ObjectCompanyReadAdapter(new ReadOnlyJsonEnvelopeStore(
                mapper, directory.toAbsolutePath(), 1024 * 1024, 16));

        var seed = adapter.detail("RBLX");

        assertEquals("RBLX", ((TextValue) seed.profile().fields().get("ticker")).value());
        assertEquals("Roblox Corporation", ((TextValue) seed.profile().fields().get("name")).value());
        assertEquals("NYSE", ((TextValue) seed.profile().fields().get("exchange")).value());
        assertEquals("7372", ((TextValue) seed.profile().fields().get("sic")).value());
        assertTrue(seed.filings().values().isEmpty());
        assertThrows(io.macrosquare.company.application.port.out.CompanyReadUnavailableException.class,
                () -> adapter.detail("NVDA"));
    }

    private void write(String name, String value) throws Exception {
        Files.writeString(directory.resolve(name),
                "{\"key\":\"test\",\"updatedAt\":\"2026-08-06T00:00:00Z\",\"value\":" + value + "}");
    }
}
