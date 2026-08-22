package io.macrosquare.research.application.port.in;

public final class ResearchThemeNotFoundException extends RuntimeException {
    public ResearchThemeNotFoundException() {
        super("theme not found");
    }
}
