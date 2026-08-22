package io.macrosquare.notification.application.port.out;

/** The durable notification deduplication state could not be read or written safely. */
public final class NotificationStatePersistenceException extends RuntimeException {

    public NotificationStatePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotificationStatePersistenceException(String message) {
        super(message);
    }
}
