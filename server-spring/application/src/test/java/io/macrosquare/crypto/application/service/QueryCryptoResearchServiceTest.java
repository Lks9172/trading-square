package io.macrosquare.crypto.application.service;

import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.port.in.CryptoSymbolNotFoundException;
import io.macrosquare.crypto.application.port.out.LoadCryptoResearchPort;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryCryptoResearchServiceTest {

    @Test
    void normalizesSymbolsBeforeLoadingDetail() {
        var observed = new AtomicReference<String>();
        var service = new QueryCryptoResearchService(new StubPort() {
            @Override
            public Research loadDetail(String symbol) {
                observed.set(symbol);
                return null;
            }
        });

        assertNull(service.detail("  btc  "));
        assertEquals("BTC", observed.get());
    }

    @Test
    void rejectsBlankSymbolsWithTheLegacyMessage() {
        var service = new QueryCryptoResearchService(new StubPort());

        var error = assertThrows(CryptoSymbolNotFoundException.class, () -> service.detail("  "));
        assertEquals("crypto symbol not found: ", error.getMessage());
    }

    private static class StubPort implements LoadCryptoResearchPort {
        @Override
        public Catalog loadCatalog() {
            return null;
        }

        @Override
        public Research loadDetail(String symbol) {
            return null;
        }
    }
}
