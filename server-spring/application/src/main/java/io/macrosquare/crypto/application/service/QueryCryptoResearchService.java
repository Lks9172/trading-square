package io.macrosquare.crypto.application.service;

import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.port.in.CryptoSymbolNotFoundException;
import io.macrosquare.crypto.application.port.in.EnrichCryptoResearchUseCase;
import io.macrosquare.crypto.application.port.in.QueryCryptoResearchUseCase;
import io.macrosquare.crypto.application.port.out.LoadCryptoResearchPort;

import java.util.Locale;
import java.util.Objects;

public final class QueryCryptoResearchService implements QueryCryptoResearchUseCase {

    private final LoadCryptoResearchPort researchPort;
    private final EnrichCryptoResearchUseCase enrichment;

    public QueryCryptoResearchService(LoadCryptoResearchPort researchPort) {
        this(researchPort, new EnrichCryptoResearchUseCase() {
            @Override public Catalog enrich(Catalog baseline) { return baseline; }
            @Override public Research enrich(Research baseline) { return baseline; }
        });
    }

    public QueryCryptoResearchService(
            LoadCryptoResearchPort researchPort,
            EnrichCryptoResearchUseCase enrichment
    ) {
        this.researchPort = Objects.requireNonNull(researchPort);
        this.enrichment = Objects.requireNonNull(enrichment);
    }

    @Override
    public Catalog catalog() {
        return enrichment.enrich(researchPort.loadCatalog());
    }

    @Override
    public Research detail(String symbol) {
        var normalized = normalizeSymbol(symbol);
        return enrichment.enrich(researchPort.loadDetail(normalized));
    }

    private static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new CryptoSymbolNotFoundException("");
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
