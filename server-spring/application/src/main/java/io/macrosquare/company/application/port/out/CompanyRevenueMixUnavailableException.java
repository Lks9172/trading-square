package io.macrosquare.company.application.port.out;

public final class CompanyRevenueMixUnavailableException extends RuntimeException {
    public CompanyRevenueMixUnavailableException(String message) {
        super(message);
    }

    public CompanyRevenueMixUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
