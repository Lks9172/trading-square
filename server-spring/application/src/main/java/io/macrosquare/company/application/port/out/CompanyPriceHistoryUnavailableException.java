package io.macrosquare.company.application.port.out;

public final class CompanyPriceHistoryUnavailableException extends RuntimeException {

    public CompanyPriceHistoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
