package io.macrosquare.crypto.adapter.out.persistence;

import io.macrosquare.crypto.adapter.out.json.CryptoResearchJsonMapper;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.port.in.CryptoSymbolNotFoundException;
import io.macrosquare.crypto.application.port.out.CryptoResearchUnavailableException;
import io.macrosquare.crypto.application.port.out.LoadCryptoResearchPort;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Reads the complete five-asset crypto research projections from persisted route caches. */
public final class FileCryptoResearchAdapter implements LoadCryptoResearchPort {

    private static final Set<String> SYMBOLS = Set.of("BTC", "ETH", "SOL", "XRP", "BNB");
    private final JsonEnvelopeStore store;

    public FileCryptoResearchAdapter(JsonEnvelopeStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public Catalog loadCatalog() {
        return CryptoResearchJsonMapper.mapCatalog(required(
                "route_research-crypto_v1.json", "crypto catalog"));
    }

    @Override
    public Research loadDetail(String symbol) {
        var normalized = symbol.toUpperCase(Locale.ROOT);
        if (!SYMBOLS.contains(normalized)) throw new CryptoSymbolNotFoundException(normalized);
        return CryptoResearchJsonMapper.mapResearch(required(
                "route_research-crypto-detail_v1_" + normalized.toLowerCase(Locale.ROOT) + ".json",
                "crypto detail " + normalized
        ));
    }

    private tools.jackson.databind.JsonNode required(String fileName, String label) {
        try {
            return store.findValue(fileName).orElseThrow(() ->
                    new CryptoResearchUnavailableException("Persisted " + label + " projection is unavailable"));
        } catch (CryptoResearchUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CryptoResearchUnavailableException("Unable to normalize persisted " + label, error);
        }
    }
}
