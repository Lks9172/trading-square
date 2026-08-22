package io.macrosquare.crypto.application.port.out;

import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;

public interface LoadCryptoResearchPort {
    Catalog loadCatalog();

    Research loadDetail(String symbol);
}
