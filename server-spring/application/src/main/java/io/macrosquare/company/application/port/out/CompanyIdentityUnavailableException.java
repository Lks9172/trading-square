package io.macrosquare.company.application.port.out;

public final class CompanyIdentityUnavailableException extends RuntimeException {

    public CompanyIdentityUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
