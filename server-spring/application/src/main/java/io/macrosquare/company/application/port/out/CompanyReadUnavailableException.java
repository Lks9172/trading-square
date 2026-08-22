package io.macrosquare.company.application.port.out;

public final class CompanyReadUnavailableException extends RuntimeException {

    public CompanyReadUnavailableException(String message) {
        super(message);
    }

    public CompanyReadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
