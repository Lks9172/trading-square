package io.macrosquare.research.application.port.in;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDate;

public record CurrentSectorRotationCommand(
        String calculatedAt,
        Map<String, Double> rawValues,
        Map<String, Double> derivedValues,
        Map<String, LocalDate> rawObservedOn,
        Map<String, LocalDate> derivedObservedOn,
        String macroRegime
) {
    public CurrentSectorRotationCommand {
        if (calculatedAt == null || calculatedAt.isBlank()) {
            throw new IllegalArgumentException("calculatedAt is required");
        }
        if (macroRegime == null || macroRegime.isBlank()) {
            throw new IllegalArgumentException("macroRegime is required");
        }
        rawValues = immutable(rawValues);
        derivedValues = immutable(derivedValues);
        rawObservedOn = immutableDates(rawObservedOn);
        derivedObservedOn = immutableDates(derivedObservedOn);
    }

    public CurrentSectorRotationCommand(
            String calculatedAt,
            Map<String, Double> rawValues,
            Map<String, Double> derivedValues,
            String macroRegime
    ) {
        this(calculatedAt, rawValues, derivedValues, Map.of(), Map.of(), macroRegime);
    }

    private static Map<String, Double> immutable(Map<String, Double> source) {
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(source == null ? Map.of() : source));
    }

    private static Map<String, LocalDate> immutableDates(Map<String, LocalDate> source) {
        return java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(source == null ? Map.of() : source));
    }
}
