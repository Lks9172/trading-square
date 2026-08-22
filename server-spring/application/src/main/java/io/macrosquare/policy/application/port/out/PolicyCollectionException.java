package io.macrosquare.policy.application.port.out;

public final class PolicyCollectionException extends RuntimeException {
    public PolicyCollectionException(String message, Throwable cause) {
        super(message, cause);
    }

    public PolicyCollectionException(String message) {
        super(message);
    }
}
