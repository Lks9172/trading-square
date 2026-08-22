package io.macrosquare.policy.application.port.out;

public final class PolicyPersistenceException extends RuntimeException {
    public PolicyPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
