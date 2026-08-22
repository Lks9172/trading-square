package io.macrosquare.research.adapter.out.json;

import io.macrosquare.research.domain.rotation.MacroRegime;
import io.macrosquare.research.domain.rotation.RotationRegimeAssessment;
import io.macrosquare.research.domain.rotation.SectorRotationRegime;
import io.macrosquare.research.application.model.NarrativeExternalQueries;
import io.macrosquare.research.application.model.NarrativeHistoryPoint;
import io.macrosquare.research.application.model.NarrativeSnapshotMetadata;
import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.model.NarrativeTrend;
import io.macrosquare.research.application.model.ResearchSnapshot;
import io.macrosquare.research.domain.narrative.AssetSignalAction;
import io.macrosquare.research.domain.narrative.NarrativeExternalSignal;
import io.macrosquare.research.domain.narrative.NarrativeManualEvidence;
import io.macrosquare.research.domain.narrative.NarrativeProxyScore;
import io.macrosquare.research.domain.narrative.NarrativeStage;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.research.domain.narrative.NarrativeThemeState;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes the persisted snapshot JSON contract into application-owned values.
 *
 * <p>This mapper is deliberately adapter-scoped. Jackson and the legacy JSON
 * shape never cross the application port.</p>
 */
public final class ResearchSnapshotJsonMapper {

    private ResearchSnapshotJsonMapper() {
    }

    public static ResearchSnapshot map(JsonNode root) {
        var timestamp = requiredText(root, "timestamp");
        var rawValues = valueMap(requiredObject(root, "raw"));
        var derivedValues = valueMap(requiredObject(root, "derived"));
        var regime = MacroRegime.valueOf(requiredText(requiredObject(root, "regime"), "regime"));
        var signals = assetSignals(requiredArray(root, "signals"));
        var manual = manualEvidence(requiredAt(root, "/meta/profile/manualInputs"));
        var narratives = narratives(requiredAt(root, "/meta/narratives"));
        var rotation = rotationAssessment(requiredAt(root, "/meta/topdown/rotation"));
        return new ResearchSnapshot(
                timestamp,
                rawValues,
                derivedValues,
                regime,
                signals,
                manual,
                narratives.states(),
                narratives.metadata(),
                rotation
        );
    }

    private static Map<String, Double> valueMap(JsonNode object) {
        if (!object.isObject()) throw new IllegalArgumentException("Expected indicator object");
        var values = new LinkedHashMap<String, Double>();
        object.properties().forEach(entry -> {
            var eligibleNode = entry.getValue().path("eligibleForSignals");
            if (eligibleNode.isBoolean() && !eligibleNode.asBoolean()) return;
            var valueNode = entry.getValue().path("value");
            values.put(entry.getKey(), valueNode.isNumber() ? valueNode.asDouble() : null);
        });
        return values;
    }

    private static Map<String, AssetSignalAction> assetSignals(JsonNode array) {
        var signals = new LinkedHashMap<String, AssetSignalAction>();
        for (var item : array) {
            signals.put(
                    requiredText(item, "asset"),
                    AssetSignalAction.valueOf(requiredText(item, "signal"))
            );
        }
        return signals;
    }

    private static NarrativeManualEvidence manualEvidence(JsonNode node) {
        return new NarrativeManualEvidence(
                integerOrNull(node.path("geoRisk")),
                integerOrNull(node.path("aiNarrativeStrength"))
        );
    }

    private static NarrativePayload narratives(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("Expected narrative array");
        var states = new EnumMap<NarrativeTheme, NarrativeThemeState>(NarrativeTheme.class);
        var metadata = new EnumMap<NarrativeTheme, NarrativeSnapshotMetadata>(NarrativeTheme.class);
        for (var item : array) {
            var themeNode = requiredObject(item, "theme");
            var theme = NarrativeTheme.fromId(requiredText(themeNode, "id"));
            var externalQueriesNode = requiredObject(themeNode, "externalQueries");
            var definition = new NarrativeThemeDefinition(
                    theme,
                    requiredText(themeNode, "title"),
                    requiredText(themeNode, "description"),
                    textList(requiredArray(themeNode, "proxies")),
                    new NarrativeExternalQueries(
                            requiredText(externalQueriesNode, "youtubeQuery"),
                            requiredText(externalQueriesNode, "newsQuery")
                    )
            );
            var proxyScores = new ArrayList<NarrativeProxyScore>();
            for (var proxy : requiredArray(item, "proxyScores")) {
                proxyScores.add(new NarrativeProxyScore(
                        requiredText(proxy, "key"),
                        requiredText(proxy, "label"),
                        requiredNumber(proxy, "score"),
                        requiredText(proxy, "detail")
                ));
            }
            var externalSignals = new ArrayList<NarrativeExternalSignal>();
            for (var signal : requiredArray(item, "externalSignals")) {
                externalSignals.add(new NarrativeExternalSignal(
                        requiredText(signal, "key"),
                        requiredText(signal, "label"),
                        numberOrNull(signal.path("value")),
                        requiredNumber(signal, "score"),
                        requiredText(signal, "detail")
                ));
            }
            states.put(theme, new NarrativeThemeState(
                    theme,
                    NarrativeStage.valueOf(requiredText(item, "stage")),
                    requiredInt(item, "heatScore"),
                    textList(requiredArray(item, "drivers")),
                    textList(requiredArray(item, "risks")),
                    proxyScores,
                    externalSignals
            ));
            var history = new ArrayList<NarrativeHistoryPoint>();
            for (var point : requiredArray(item, "heatHistory")) {
                history.add(new NarrativeHistoryPoint(
                        requiredText(point, "date"),
                        requiredInt(point, "heatScore")
                ));
            }
            metadata.put(theme, new NarrativeSnapshotMetadata(
                    definition,
                    requiredText(item, "generatedAt"),
                    NarrativeTrend.valueOf(requiredText(item, "trend")),
                    nullableInt(item, "heatDelta7d"),
                    nullableInt(item, "heatDelta30d"),
                    history
            ));
        }
        return new NarrativePayload(states, metadata);
    }

    private static RotationRegimeAssessment rotationAssessment(JsonNode node) {
        var scoresNode = requiredObject(node, "regimeScores");
        var scores = new EnumMap<SectorRotationRegime, Integer>(SectorRotationRegime.class);
        for (var regime : SectorRotationRegime.values()) {
            scores.put(regime, requiredInt(scoresNode, regime.name()));
        }
        return new RotationRegimeAssessment(
                SectorRotationRegime.valueOf(requiredText(node, "regime")),
                requiredInt(node, "confidence"),
                scores
        );
    }

    private static List<String> textList(JsonNode array) {
        var values = new ArrayList<String>();
        for (var item : array) {
            if (!item.isString()) throw new IllegalArgumentException("Expected text array value");
            values.add(item.stringValue());
        }
        return List.copyOf(values);
    }

    private static JsonNode requiredAt(JsonNode root, String pointer) {
        var node = root.at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("Missing snapshot field at " + pointer);
        }
        return node;
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing object field: " + field);
        return node;
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isArray()) throw new IllegalArgumentException("Missing array field: " + field);
        return node;
    }

    private static String requiredText(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isString()) throw new IllegalArgumentException("Missing text field: " + field);
        return node.stringValue();
    }

    private static int requiredInt(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException("Missing number field: " + field);
        return node.asInt();
    }

    private static Integer nullableInt(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Expected nullable number field: " + field);
        return node.asInt();
    }

    private static double requiredNumber(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException("Missing number field: " + field);
        return node.asDouble();
    }

    private static Integer integerOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static Double numberOrNull(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private record NarrativePayload(
            Map<NarrativeTheme, NarrativeThemeState> states,
            Map<NarrativeTheme, NarrativeSnapshotMetadata> metadata
    ) {
    }
}
