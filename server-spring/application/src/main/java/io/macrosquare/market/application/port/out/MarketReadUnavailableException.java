package io.macrosquare.market.application.port.out;

public final class MarketReadUnavailableException extends RuntimeException {

    public MarketReadUnavailableException(String message) {
        super(message);
    }

    public MarketReadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
