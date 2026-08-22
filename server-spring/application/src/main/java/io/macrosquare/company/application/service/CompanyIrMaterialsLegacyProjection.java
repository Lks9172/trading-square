package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.domain.model.CompanyIrMaterial;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Anti-corruption projection of the serving Node IR-material contract. */
final class CompanyIrMaterialsLegacyProjection {

    private CompanyIrMaterialsLegacyProjection() {
    }

    static List<CompanyIrMaterial> from(Research research) {
        var materials = new ArrayList<CompanyIrMaterial>();
        for (var value : research.irMaterials().values()) {
            var material = requiredObject(value, "irMaterials[]");
            materials.add(new CompanyIrMaterial(
                    requiredText(material, "title"),
                    requiredText(material, "form"),
                    requiredDate(material, "filingDate"),
                    requiredText(material, "url"),
                    CompanyIrMaterial.Type.fromValue(requiredText(material, "type")),
                    CompanyIrMaterial.Source.fromValue(requiredText(material, "source")),
                    CompanyIrMaterial.ContentType.fromValue(requiredText(material, "contentType")),
                    nullableTextIfPresent(material, "summary")
            ));
        }
        return List.copyOf(materials);
    }

    private static LocalDate requiredDate(ObjectValue object, String field) {
        try {
            return LocalDate.parse(requiredText(object, field));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be an ISO date", error);
        }
    }

    private static String requiredText(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(field + " must be non-blank text");
    }

    private static String nullableTextIfPresent(ObjectValue object, String field) {
        var value = object.fields().get(field);
        if (value == null || value == NullValue.INSTANCE) return null;
        if (value instanceof TextValue text) return text.value();
        throw new IllegalArgumentException(field + " must be text or null");
    }

    private static ObjectValue requiredObject(StructuredValue value, String field) {
        if (value instanceof ObjectValue object) return object;
        throw new IllegalArgumentException(field + " must be an object");
    }

    private static StructuredValue required(ObjectValue object, String field) {
        var value = object.fields().get(field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
