package io.macrosquare.shared.application.port.out;

/**
 * Framework-neutral port for reporting a deliberately degraded operation.
 *
 * <p>Application services use this only when continuing with a last-valid value is
 * part of the use-case contract. Unexpected failures that invalidate the command
 * still propagate to the caller.</p>
 */
@FunctionalInterface
public interface OperationalEventSink {

    void degraded(String component, String operation, String reference, Throwable cause);

    static OperationalEventSink noop() {
        return (component, operation, reference, cause) -> {
        };
    }
}
