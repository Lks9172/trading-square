package io.macrosquare.market.adapter.out.json;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

public final class MarketReadJsonMapper {

    private MarketReadJsonMapper() {
    }

    public static Document mapObject(JsonNode root) {
        requireObject(root, "request");
        return document(root);
    }

    public static Document mapSnapshot(JsonNode root) {
        requireObject(root, "snapshot");
        requiredNonBlankText(root, "timestamp");
        requiredObject(root, "raw");
        requiredObject(root, "derived");
        requiredObject(root, "regime");
        requiredArray(root, "signals");
        requiredObject(root, "allocation");
        requiredObject(root, "meta");
        return document(root);
    }

    public static Document mapCoverage(JsonNode root) {
        requireObject(root, "history coverage");
        root.properties().forEach(entry -> {
            var coverage = requireObject(entry.getValue(), "coverage entry " + entry.getKey());
            requiredNonNegativeInteger(coverage, "count");
            requiredText(coverage, "oldest");
            requiredText(coverage, "newest");
            requiredNonNegativeInteger(coverage, "guaranteedYears");
        });
        return document(root);
    }

    public static Document mapHistory(JsonNode root, String expectedSource, String expectedKey) {
        requireObject(root, "history response");
        var source = requiredText(root, "source");
        var key = requiredText(root, "key");
        if (!expectedSource.equals(source) || !expectedKey.equals(key)) {
            throw new IllegalArgumentException("history response identity does not match the request");
        }
        var count = requiredNonNegativeInteger(root, "count");
        var points = requiredArray(root, "points");
        validatePoints(points, "history points");
        if (count != points.size()) throw new IllegalArgumentException("history count does not match points");
        return document(root);
    }

    public static Document mapSeries(JsonNode root, List<String> expectedKeys, String expectedRange, String expectedInterval) {
        requireObject(root, "history series response");
        var keysNode = requiredArray(root, "keys");
        var actualKeys = new ArrayList<String>(keysNode.size());
        for (var item : keysNode) {
            if (!item.isString()) throw new IllegalArgumentException("history series key must be text");
            actualKeys.add(item.stringValue());
        }
        if (!expectedKeys.equals(actualKeys)) throw new IllegalArgumentException("history series keys do not match");
        if (!expectedRange.equals(requiredText(root, "range"))) {
            throw new IllegalArgumentException("history series range does not match");
        }
        if (!expectedInterval.equals(requiredText(root, "interval"))) {
            throw new IllegalArgumentException("history series interval does not match");
        }

        var expectedNames = new HashSet<>(expectedKeys);
        var series = requiredObject(root, "series");
        series.properties().forEach(entry -> {
            if (!expectedNames.contains(entry.getKey())) {
                throw new IllegalArgumentException("history series contains an unexpected key");
            }
            validatePoints(requireArray(entry.getValue(), "series " + entry.getKey()), "series " + entry.getKey());
        });
        return document(root);
    }

    private static void validatePoints(JsonNode points, String label) {
        for (var point : points) {
            var object = requireObject(point, label + " point");
            requiredNonBlankText(object, "date");
            var value = requiredField(object, "value");
            if (!value.isNumber()) throw new IllegalArgumentException(label + " value must be numeric");
        }
    }

    private static Document document(JsonNode root) {
        return new Document((ObjectValue) structuredValue(root));
    }

    private static StructuredValue structuredValue(JsonNode node) {
        if (node.isNull()) return NullValue.INSTANCE;
        if (node.isObject()) {
            var fields = new LinkedHashMap<String, StructuredValue>(node.size());
            node.properties().forEach(entry -> fields.put(entry.getKey(), structuredValue(entry.getValue())));
            return new ObjectValue(fields);
        }
        if (node.isArray()) {
            var values = new ArrayList<StructuredValue>(node.size());
            node.forEach(value -> values.add(structuredValue(value)));
            return new ArrayValue(values);
        }
        if (node.isString()) return new TextValue(node.stringValue());
        if (node.isBoolean()) return new BooleanValue(node.booleanValue());
        if (node.isIntegralNumber()) return new NumberValue(node.longValue());
        if (node.isNumber()) return new NumberValue(node.decimalValue());
        throw new IllegalArgumentException("unsupported market read value type: " + node.getNodeType());
    }

    private static JsonNode requiredField(JsonNode node, String field) {
        requireObject(node, "object containing " + field);
        if (!node.has(field)) throw new IllegalArgumentException(field + " is required");
        return node.get(field);
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        return requireObject(requiredField(node, field), field);
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        return requireArray(requiredField(node, field), field);
    }

    private static String requiredNonBlankText(JsonNode node, String field) {
        var value = requiredText(node, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (!value.isString()) throw new IllegalArgumentException(field + " must be text");
        return value.stringValue();
    }

    private static int requiredNonNegativeInteger(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.intValue();
    }

    private static JsonNode requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return node;
    }

    private static JsonNode requireArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return node;
    }
}
