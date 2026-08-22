package io.macrosquare.market.application.port.out;

public final class MarketObservationPersistenceException extends RuntimeException {
    public MarketObservationPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
