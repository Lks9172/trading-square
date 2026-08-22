package io.macrosquare.compatibility.adapter.in.web;

import io.macrosquare.compatibility.application.model.SupplementalApiModels.ArrayValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.BooleanValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.NullValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.NumberValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.ObjectValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.StructuredValue;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextValue;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;

final class SupplementalApiWebMapper {

    private SupplementalApiWebMapper() {
    }

    static Document request(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("request body must be an object");
        return new Document((ObjectValue) applicationValue(root, 0));
    }

    static Object response(Document document) {
        return webValue(document.root());
    }

    private static StructuredValue applicationValue(JsonNode node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("request body is too deep");
        if (node == null || node.isNull()) return NullValue.INSTANCE;
        if (node.isString()) return new TextValue(node.stringValue());
        if (node.isBoolean()) return new BooleanValue(node.booleanValue());
        if (node.isIntegralNumber()) return new NumberValue(node.longValue());
        if (node.isNumber()) return new NumberValue(node.decimalValue());
        if (node.isArray()) {
            if (node.size() > 10_000) throw new IllegalArgumentException("request array is too large");
            var result = new ArrayList<StructuredValue>();
            node.forEach(value -> result.add(applicationValue(value, depth + 1)));
            return new ArrayValue(result);
        }
        if (node.isObject()) {
            if (node.size() > 1_000) throw new IllegalArgumentException("request object has too many fields");
            var result = new LinkedHashMap<String, StructuredValue>();
            node.properties().forEach(entry -> result.put(entry.getKey(), applicationValue(entry.getValue(), depth + 1)));
            return new ObjectValue(result);
        }
        throw new IllegalArgumentException("unsupported request value");
    }

    private static Object webValue(StructuredValue value) {
        return switch (value) {
            case NullValue ignored -> null;
            case TextValue text -> text.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case ArrayValue array -> array.values().stream().map(SupplementalApiWebMapper::webValue).toList();
            case ObjectValue object -> {
                var result = new LinkedHashMap<String, Object>();
                object.fields().forEach((key, item) -> result.put(key, webValue(item)));
                yield result;
            }
        };
    }
}
