package io.macrosquare.compatibility.application.port.out;

public class SupplementalApiUnavailableException extends RuntimeException {
    public SupplementalApiUnavailableException(String message) {
        super(message);
    }

    public SupplementalApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
