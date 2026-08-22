package io.macrosquare.institutional.application.port.out;

/** Anti-corruption port exposing only the analyst score needed by institutional analysis. */
@FunctionalInterface
public interface LoadInstitutionalAnalystScorePort {
    Double load(String ticker);
}
