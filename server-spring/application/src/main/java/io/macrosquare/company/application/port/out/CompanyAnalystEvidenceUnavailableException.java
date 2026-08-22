package io.macrosquare.company.application.port.out;

public final class CompanyAnalystEvidenceUnavailableException extends RuntimeException {

    public CompanyAnalystEvidenceUnavailableException(String message) {
        super(message);
    }

    public CompanyAnalystEvidenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
