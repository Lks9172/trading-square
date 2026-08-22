package io.macrosquare.crypto.application.port.out;

import io.macrosquare.crypto.application.model.CryptoPricePoint;

import java.util.List;

public interface LoadCryptoMarketSeriesPort {
    List<CryptoPricePoint> load(String symbol);
}
