package io.macrosquare.research.application.port.out;

public final class ResearchSnapshotUnavailableException extends RuntimeException {

    public ResearchSnapshotUnavailableException(String message) {
        super(message);
    }

    public ResearchSnapshotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
