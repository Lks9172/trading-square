package io.macrosquare.crypto.adapter.out.legacy;

import io.macrosquare.crypto.CryptoResearchFixture;
import io.macrosquare.crypto.adapter.out.persistence.FileCryptoResearchAdapter;
import io.macrosquare.crypto.application.port.in.CryptoSymbolNotFoundException;
import io.macrosquare.crypto.application.port.out.CryptoResearchUnavailableException;
import io.macrosquare.shared.adapter.out.persistence.ReadOnlyJsonEnvelopeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileCryptoResearchAdapterTest {

    @TempDir
    Path directory;

    @Test
    void readsThePersistedCatalogAndFiveAssetDetailProjection() throws Exception {
        var mapper = new ObjectMapper();
        write("route_research-crypto_v1.json", mapper.writeValueAsString(CryptoResearchFixture.catalog()));
        write("route_research-crypto-detail_v1_btc.json",
                mapper.writeValueAsString(CryptoResearchFixture.research(true)));
        var adapter = adapter();

        assertEquals("RISK_ON", adapter.loadCatalog().marketRegime().regime());
        assertEquals(64653.91, adapter.loadDetail("BTC").market().price().doubleValue());
        assertEquals(64653.91, adapter.loadDetail("btc").market().price().doubleValue());
    }

    @Test
    void separatesUnknownSymbolsFromMissingKnownProjectionFiles() {
        var adapter = adapter();

        assertThrows(CryptoSymbolNotFoundException.class, () -> adapter.loadDetail("DOGE"));
        assertThrows(CryptoResearchUnavailableException.class, () -> adapter.loadDetail("BTC"));
        assertThrows(CryptoResearchUnavailableException.class, adapter::loadCatalog);
    }

    private FileCryptoResearchAdapter adapter() {
        return new FileCryptoResearchAdapter(new ReadOnlyJsonEnvelopeStore(
                new ObjectMapper(), directory.toAbsolutePath(), 1024 * 1024, 16));
    }

    private void write(String fileName, String value) throws Exception {
        Files.writeString(directory.resolve(fileName),
                "{\"key\":\"test\",\"updatedAt\":\"2026-07-20T00:00:00Z\",\"value\":" + value + "}");
    }
}
