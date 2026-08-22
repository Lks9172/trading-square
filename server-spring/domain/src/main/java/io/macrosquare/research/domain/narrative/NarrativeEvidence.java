package io.macrosquare.research.domain.narrative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record NarrativeEvidence(
        Map<String, Double> rawValues,
        Map<String, Double> derivedValues,
        Map<String, AssetSignalAction> assetSignals,
        NarrativeManualEvidence manual,
        List<NarrativeExternalSignal> externalSignals
) {
    public NarrativeEvidence {
        rawValues = immutableFiniteMap(rawValues, "rawValues");
        derivedValues = immutableFiniteMap(derivedValues, "derivedValues");
        var signals = new LinkedHashMap<String, AssetSignalAction>();
        if (assetSignals != null) {
            assetSignals.forEach((key, value) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException("asset signal key is required");
                if (value == null) throw new IllegalArgumentException("asset signal value is required");
                signals.put(key, value);
            });
        }
        assetSignals = Collections.unmodifiableMap(signals);
        manual = manual == null ? NarrativeManualEvidence.empty() : manual;
        externalSignals = List.copyOf(externalSignals == null ? List.of() : externalSignals);
    }

    private static Map<String, Double> immutableFiniteMap(Map<String, Double> source, String field) {
        var values = new LinkedHashMap<String, Double>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException(field + " key is required");
                if (value != null && !Double.isFinite(value)) {
                    throw new IllegalArgumentException(field + " values must be finite");
                }
                values.put(key, value);
            });
        }
        return Collections.unmodifiableMap(values);
    }
}
