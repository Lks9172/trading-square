package io.macrosquare.company.adapter.out.json;

import io.macrosquare.company.application.model.CompanyReadModels.SearchItem;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.Summary;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class CompanyReadJsonMapper {

    private CompanyReadJsonMapper() {
    }

    public static SearchResult mapSearch(JsonNode root) {
        var items = new ArrayList<SearchItem>();
        for (var node : requiredArray(root, "items")) {
            items.add(new SearchItem(
                    requiredText(node, "ticker"),
                    requiredText(node, "cik"),
                    requiredText(node, "title")
            ));
        }
        return new SearchResult(items);
    }

    public static SummaryResult mapSummaries(JsonNode root) {
        var items = new ArrayList<Summary>();
        for (var node : requiredArray(root, "items")) {
            items.add(new Summary(
                    requiredText(node, "ticker"),
                    requiredText(node, "name"),
                    nullableInt(node, "totalScore"),
                    nullableInt(node, "buyScore"),
                    nullableText(node, "buyLabel"),
                    nullableNumber(node, "revenueGrowthYoY"),
                    nullableNumber(node, "operatingMargin"),
                    nullableNumber(node, "evToSales"),
                    nullableInt(node, "crowdingScore"),
                    nullableInt(node, "appealScore"),
                    nullableText(node, "bottomState"),
                    nullableInt(node, "earningsBottomScore"),
                    nullableInt(node, "priceBottomScore"),
                    nullableInt(node, "volumeConfirmationScore"),
                    nullableInt(node, "failureRiskScore")
            ));
        }
        return new SummaryResult(items);
    }

    public static Research mapResearch(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("company research must be an object");
        }
        return new Research(
                requiredObjectValue(root, "profile"),
                requiredObjectValue(root, "quote"),
                requiredObjectValue(root, "financials"),
                requiredObjectValue(root, "score"),
                requiredObjectValue(root, "buyScore"),
                requiredArrayValue(root, "filings"),
                requiredArrayValue(root, "irMaterials"),
                requiredArrayValue(root, "highlights"),
                optionalStructuredValue(root, "peerGroup"),
                optionalStructuredValue(root, "bottleneck"),
                optionalStructuredValue(root, "narrative"),
                optionalStructuredValue(root, "capitalFlow"),
                optionalStructuredValue(root, "cashFlowQuality"),
                optionalStructuredValue(root, "multipleInsight"),
                optionalStructuredValue(root, "guidanceInsight"),
                optionalStructuredValue(root, "timeframeView"),
                optionalStructuredValue(root, "correctionAssessment"),
                optionalStructuredValue(root, "thesisMonitor"),
                optionalStructuredValue(root, "reversalConfirmation"),
                optionalStructuredValue(root, "sectorContext"),
                optionalStructuredValue(root, "verdicts"),
                optionalStructuredValue(root, "bottomSignal"),
                optionalStructuredValue(root, "positionSizing"),
                optionalStructuredValue(root, "executionBridge"),
                requiredArrayValue(root, "peers")
        );
    }

    private static ObjectValue requiredObjectValue(JsonNode node, String field) {
        var value = requiredStructuredValue(node, field);
        if (value instanceof ObjectValue objectValue) return objectValue;
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static ArrayValue requiredArrayValue(JsonNode node, String field) {
        var value = requiredStructuredValue(node, field);
        if (value instanceof ArrayValue arrayValue) return arrayValue;
        throw new IllegalArgumentException(field + " must be an array");
    }

    private static StructuredValue requiredStructuredValue(JsonNode node, String field) {
        return structuredValue(requiredField(node, field));
    }

    private static StructuredValue optionalStructuredValue(JsonNode node, String field) {
        return node != null && node.isObject() && node.has(field)
                ? structuredValue(node.get(field))
                : NullValue.INSTANCE;
    }

    private static StructuredValue structuredValue(JsonNode node) {
        if (node.isNull()) return NullValue.INSTANCE;
        if (node.isObject()) {
            var fields = new LinkedHashMap<String, StructuredValue>();
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
        throw new IllegalArgumentException("unsupported company research value type: " + node.getNodeType());
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (!value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.stringValue();
    }

    private static String nullableText(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException(field + " must be text or null");
        return value.stringValue();
    }

    private static Integer nullableInt(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (value.isNull()) return null;
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer or null");
        }
        var number = value.asDouble();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be an integer or null");
        }
        return (int) number;
    }

    private static BigDecimal nullableNumber(JsonNode node, String field) {
        var value = requiredField(node, field);
        if (value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(field + " must be numeric or null");
        return BigDecimal.valueOf(value.asDouble());
    }

    private static JsonNode requiredField(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.has(field)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return node.get(field);
    }
}
