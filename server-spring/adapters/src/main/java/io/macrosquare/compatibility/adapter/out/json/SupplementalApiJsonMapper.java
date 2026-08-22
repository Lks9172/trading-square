package io.macrosquare.compatibility.adapter.out.json;

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

public final class SupplementalApiJsonMapper {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_NODES = 500_000;

    private SupplementalApiJsonMapper() {
    }

    public static Document document(JsonNode root, Contract contract) {
        requireObject(root, contract.label);
        contract.validate(root);
        var counter = new Counter();
        return new Document((ObjectValue) structured(root, 0, counter));
    }

    public static Object outbound(Document document) {
        return webValue(document.root());
    }

    private static StructuredValue structured(JsonNode node, int depth, Counter counter) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("supplemental response is too deep");
        if (++counter.value > MAX_NODES) throw new IllegalArgumentException("supplemental response is too large");
        if (node == null || node.isNull()) return NullValue.INSTANCE;
        if (node.isString()) return new TextValue(node.stringValue());
        if (node.isBoolean()) return new BooleanValue(node.booleanValue());
        if (node.isIntegralNumber()) return new NumberValue(node.longValue());
        if (node.isNumber()) return new NumberValue(node.decimalValue());
        if (node.isArray()) {
            var values = new ArrayList<StructuredValue>(node.size());
            node.forEach(value -> values.add(structured(value, depth + 1, counter)));
            return new ArrayValue(values);
        }
        if (node.isObject()) {
            var values = new LinkedHashMap<String, StructuredValue>();
            node.properties().forEach(entry -> values.put(entry.getKey(), structured(entry.getValue(), depth + 1, counter)));
            return new ObjectValue(values);
        }
        throw new IllegalArgumentException("unsupported supplemental response value");
    }

    private static Object webValue(StructuredValue value) {
        return switch (value) {
            case NullValue ignored -> null;
            case TextValue text -> text.value();
            case NumberValue number -> number.value();
            case BooleanValue bool -> bool.value();
            case ArrayValue array -> array.values().stream().map(SupplementalApiJsonMapper::webValue).toList();
            case ObjectValue object -> {
                var result = new LinkedHashMap<String, Object>();
                object.fields().forEach((key, item) -> result.put(key, webValue(item)));
                yield result;
            }
        };
    }

    public enum Contract {
        SNAPSHOT("snapshot") {
            @Override void validate(JsonNode root) {
                required(root, "timestamp");
                requiredObject(root, "raw");
                requiredObject(root, "derived");
                requiredObject(root, "regime");
                requiredArray(root, "signals");
                requiredObject(root, "allocation");
                requiredObject(root, "meta");
            }
        },
        SMART_MONEY("smart money") {
            @Override void validate(JsonNode root) { required(root, "insider"); }
        },
        SECTOR_BACKTEST("sector backtest") {
            @Override void validate(JsonNode root) {
                requiredObject(root, "dateRange");
                requiredObject(root, "methodology");
                requiredObject(root, "summary");
                requiredArray(root, "recentSamples");
            }
        },
        BOTTLENECK_CATALOG("bottleneck catalog") {
            @Override void validate(JsonNode root) { requiredArray(root, "themes"); }
        },
        BOTTLENECK_DETAIL("bottleneck detail") {
            @Override void validate(JsonNode root) { }
        },
        COMPANIES("company catalog") {
            @Override void validate(JsonNode root) {
                requiredArray(root, "items");
                required(root, "total");
                required(root, "page");
                required(root, "pageSize");
                required(root, "totalPages");
                requiredArray(root, "themes");
                requiredArray(root, "sectors");
            }
        },
        HIGHLIGHTS("research highlights") {
            @Override void validate(JsonNode root) { requiredArray(root, "sectors"); requiredArray(root, "companies"); }
        },
        EARNINGS("earnings") {
            @Override void validate(JsonNode root) { requiredArray(root, "earnings"); required(root, "count"); }
        },
        CORRELATION("correlation") {
            @Override void validate(JsonNode root) {
                required(root, "lookbackDays"); requiredArray(root, "assets"); requiredArray(root, "matrix");
                requiredArray(root, "missing"); required(root, "asOf");
            }
        },
        DOMESTIC_REPORTS("domestic reports") {
            @Override void validate(JsonNode root) { required(root, "data"); }
        },
        WEEKLY_REPORT("weekly report") {
            @Override void validate(JsonNode root) { requiredObject(root, "report"); required(root, "text"); }
        },
        BACKTEST_SUMMARY("backtest summary") {
            @Override void validate(JsonNode root) { }
        },
        BACKTEST_PORTFOLIO("portfolio backtest") {
            @Override void validate(JsonNode root) { }
        },
        BACKTEST_USER_PLAN("user plan backtest") {
            @Override void validate(JsonNode root) { }
        };

        private final String label;

        Contract(String label) {
            this.label = label;
        }

        abstract void validate(JsonNode root);
    }

    private static JsonNode required(JsonNode root, String field) {
        if (!root.has(field)) throw new IllegalArgumentException(field + " is required");
        return root.get(field);
    }

    private static void requiredObject(JsonNode root, String field) {
        requireObject(required(root, field), field);
    }

    private static void requiredArray(JsonNode root, String field) {
        var value = required(root, field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
    }

    private static void requireObject(JsonNode root, String field) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException(field + " must be an object");
    }

    private static final class Counter {
        private int value;
    }
}
