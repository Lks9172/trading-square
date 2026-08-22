package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanySubmissionsSnapshot;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;

/** Anti-corruption projection of the serving Node profile and filing metadata. */
record CompanySubmissionsLegacyProjection(
        CompanySubmissionsSnapshot snapshot,
        int enrichedFilingCount
) {

    static CompanySubmissionsLegacyProjection from(Research research) {
        var profile = new CompanySubmissionsSnapshot.Profile(
                requiredText(research.profile(), "ticker"),
                requiredText(research.profile(), "cik"),
                requiredText(research.profile(), "name"),
                nullableText(research.profile(), "exchange"),
                nullableText(research.profile(), "sic")
        );
        var filings = new ArrayList<CompanySubmissionsSnapshot.Filing>();
        var enriched = 0;
        for (var item : research.filings().values()) {
            var filing = requiredObject(item, "filings[]");
            filings.add(new CompanySubmissionsSnapshot.Filing(
                    requiredText(filing, "accessionNumber"),
                    requiredDate(filing, "filingDate"),
                    requiredText(filing, "form"),
                    nullableText(filing, "primaryDocument"),
                    nullableText(filing, "primaryDocDescription"),
                    requiredBoolean(filing, "isEarningsRelated"),
                    nullableText(filing, "filingUrl")
            ));
            if (filing.fields().containsKey("summary")
                    || filing.fields().containsKey("guidanceSignals")
                    || filing.fields().containsKey("guidanceSummary")) {
                enriched++;
            }
        }
        return new CompanySubmissionsLegacyProjection(
                new CompanySubmissionsSnapshot(profile, filings),
                enriched
        );
    }

    private static String requiredText(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof TextValue text && !text.value().isBlank()) return text.value();
        throw new IllegalArgumentException(field + " must be non-blank text");
    }

    private static String nullableText(ObjectValue object, String field) {
        var value = required(object, field);
        if (value == NullValue.INSTANCE) return null;
        if (value instanceof TextValue text) return text.value();
        throw new IllegalArgumentException(field + " must be text or null");
    }

    private static boolean requiredBoolean(ObjectValue object, String field) {
        var value = required(object, field);
        if (value instanceof BooleanValue bool) return bool.value();
        throw new IllegalArgumentException(field + " must be boolean");
    }

    private static LocalDate requiredDate(ObjectValue object, String field) {
        var value = requiredText(object, field);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(field + " must be an ISO date", error);
        }
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
