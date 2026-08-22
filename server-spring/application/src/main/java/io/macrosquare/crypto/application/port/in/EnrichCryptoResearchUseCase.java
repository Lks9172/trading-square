package io.macrosquare.crypto.application.port.in;

import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;

public interface EnrichCryptoResearchUseCase {
    Catalog enrich(Catalog baseline);

    Research enrich(Research baseline);
}
