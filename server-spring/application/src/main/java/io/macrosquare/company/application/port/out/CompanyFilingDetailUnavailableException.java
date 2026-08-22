package io.macrosquare.company.application.port.out;

public final class CompanyFilingDetailUnavailableException extends RuntimeException {
    public CompanyFilingDetailUnavailableException(String message) {
        super(message);
    }

    public CompanyFilingDetailUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
