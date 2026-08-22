package io.macrosquare.crypto.application.port.in;

public final class CryptoSymbolNotFoundException extends RuntimeException {

    public CryptoSymbolNotFoundException(String symbol) {
        super("crypto symbol not found: " + symbol);
    }
}
