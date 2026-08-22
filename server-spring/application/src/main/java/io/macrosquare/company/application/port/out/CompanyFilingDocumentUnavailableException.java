package io.macrosquare.company.application.port.out;

public final class CompanyFilingDocumentUnavailableException extends RuntimeException {
    public CompanyFilingDocumentUnavailableException(String message) {
        super(message);
    }

    public CompanyFilingDocumentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
