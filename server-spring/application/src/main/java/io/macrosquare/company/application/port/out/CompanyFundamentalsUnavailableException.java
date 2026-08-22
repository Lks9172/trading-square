package io.macrosquare.company.application.port.out;

public final class CompanyFundamentalsUnavailableException extends RuntimeException {
    public CompanyFundamentalsUnavailableException(String message) {
        super(message);
    }

    public CompanyFundamentalsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
