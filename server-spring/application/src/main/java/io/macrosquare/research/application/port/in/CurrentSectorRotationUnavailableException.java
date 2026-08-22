package io.macrosquare.research.application.port.in;

/** Current point-in-time inputs are insufficient to publish a sector-rotation ranking. */
public final class CurrentSectorRotationUnavailableException extends RuntimeException {

    public CurrentSectorRotationUnavailableException(String message) {
        super(message);
    }
}
