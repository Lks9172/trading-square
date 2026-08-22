package io.macrosquare.research.application.port.out;

public final class ResearchCatalogUnavailableException extends RuntimeException {
    public ResearchCatalogUnavailableException(String message) {
        super(message);
    }

    public ResearchCatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
