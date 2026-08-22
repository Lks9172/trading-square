package io.macrosquare.crypto.application.port.out;

public final class CryptoResearchUnavailableException extends RuntimeException {

    public CryptoResearchUnavailableException(String message) {
        super(message);
    }

    public CryptoResearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
