package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Anti-corruption projection of the serving Node revenue-mix fields. */
final class CompanyRevenueMixLegacyProjection {

    private CompanyRevenueMixLegacyProjection() {
    }

    static CompanyRevenueMixLegacyRead from(Research research) {
        var financials = research.financials();
        return new CompanyRevenueMixLegacyRead(
                nullableText(financials.fields().get("segmentGeoMixNote"), "segmentGeoMixNote"),
                entries(financials.fields().get("segmentMix"), "segmentMix"),
                entries(financials.fields().get("geoMix"), "geoMix")
        );
    }

    private static List<CompanyRevenueMixLegacyRead.Entry> entries(StructuredValue value, String field) {
        if (value == null || value == NullValue.INSTANCE) return List.of();
        if (!(value instanceof ArrayValue array)) throw new IllegalArgumentException(field + " must be an array");
        var result = new ArrayList<CompanyRevenueMixLegacyRead.Entry>();
        for (var item : array.values()) {
            if (!(item instanceof ObjectValue object)) {
                throw new IllegalArgumentException(field + " must contain objects only");
            }
            result.add(new CompanyRevenueMixLegacyRead.Entry(
                    requiredText(object, "label"),
                    nullableNumber(object.fields().get("value"), field + ".value"),
                    nullableText(object.fields().get("unit"), field + ".unit"),
                    nullableNumber(object.fields().get("percentOfTotal"), field + ".percentOfTotal")
            ));
        }
        return List.copyOf(result);
    }

    private static String requiredText(ObjectValue object, String field) {
        var value = object.fields().get(field);
        if (value instanceof TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(field + " must be non-blank text");
    }

    private static String nullableText(StructuredValue value, String field) {
        if (value == null || value == NullValue.INSTANCE) return null;
        if (value instanceof TextValue text) return text.value();
        throw new IllegalArgumentException(field + " must be text or null");
    }

    private static BigDecimal nullableNumber(StructuredValue value, String field) {
        if (value == null || value == NullValue.INSTANCE) return null;
        if (value instanceof NumberValue number) {
            return number.value() instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(number.value().toString());
        }
        throw new IllegalArgumentException(field + " must be numeric or null");
    }
}
