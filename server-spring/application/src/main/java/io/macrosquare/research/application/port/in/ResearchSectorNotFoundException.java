package io.macrosquare.research.application.port.in;

public final class ResearchSectorNotFoundException extends RuntimeException {
    public ResearchSectorNotFoundException() {
        super("sector not found");
    }
}
