package io.macrosquare.research.application.model;

import io.macrosquare.research.domain.rotation.MacroRegime;
import io.macrosquare.research.domain.rotation.RotationRegimeAssessment;
import io.macrosquare.research.domain.narrative.AssetSignalAction;
import io.macrosquare.research.domain.narrative.NarrativeManualEvidence;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.research.domain.narrative.NarrativeThemeState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

public record ResearchSnapshot(
        String timestamp,
        Map<String, Double> rawValues,
        Map<String, Double> derivedValues,
        MacroRegime macroRegime,
        Map<String, AssetSignalAction> assetSignals,
        NarrativeManualEvidence manualEvidence,
        Map<NarrativeTheme, NarrativeThemeState> legacyNarratives,
        Map<NarrativeTheme, NarrativeSnapshotMetadata> narrativeMetadata,
        RotationRegimeAssessment legacyRotationAssessment
) {
    public ResearchSnapshot {
        if (timestamp == null || timestamp.isBlank()) throw new IllegalArgumentException("timestamp is required");
        if (macroRegime == null) throw new IllegalArgumentException("macroRegime is required");
        if (legacyRotationAssessment == null) {
            throw new IllegalArgumentException("legacyRotationAssessment is required");
        }
        rawValues = immutableNullableValueMap(rawValues);
        derivedValues = immutableNullableValueMap(derivedValues);
        assetSignals = Collections.unmodifiableMap(new LinkedHashMap<>(assetSignals == null ? Map.of() : assetSignals));
        manualEvidence = manualEvidence == null ? NarrativeManualEvidence.empty() : manualEvidence;
        var narratives = new EnumMap<NarrativeTheme, NarrativeThemeState>(NarrativeTheme.class);
        if (legacyNarratives != null) narratives.putAll(legacyNarratives);
        legacyNarratives = Collections.unmodifiableMap(narratives);
        var metadata = new EnumMap<NarrativeTheme, NarrativeSnapshotMetadata>(NarrativeTheme.class);
        if (narrativeMetadata != null) metadata.putAll(narrativeMetadata);
        narrativeMetadata = Collections.unmodifiableMap(metadata);
    }

    private static Map<String, Double> immutableNullableValueMap(Map<String, Double> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source == null ? Map.of() : source));
    }
}
