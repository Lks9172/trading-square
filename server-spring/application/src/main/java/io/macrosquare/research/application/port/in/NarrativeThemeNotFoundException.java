package io.macrosquare.research.application.port.in;

public final class NarrativeThemeNotFoundException extends RuntimeException {
    public NarrativeThemeNotFoundException() {
        super("narrative theme not found");
    }
}
