package io.macrosquare.company.application.port.out;

public final class CompanySubmissionsUnavailableException extends RuntimeException {

    public CompanySubmissionsUnavailableException(String message) {
        super(message);
    }

    public CompanySubmissionsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
