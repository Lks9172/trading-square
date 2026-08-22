package io.macrosquare.company.application.port.out;

public final class CompanyMarketQuoteUnavailableException extends RuntimeException {

    public CompanyMarketQuoteUnavailableException(String message) {
        super(message);
    }

    public CompanyMarketQuoteUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
