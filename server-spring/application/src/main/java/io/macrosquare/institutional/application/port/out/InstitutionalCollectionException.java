package io.macrosquare.institutional.application.port.out;

public final class InstitutionalCollectionException extends RuntimeException {
    public InstitutionalCollectionException(String message, Throwable cause) {
        super(message, cause);
    }

    public InstitutionalCollectionException(String message) {
        super(message);
    }
}
