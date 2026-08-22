package io.macrosquare.institutional.adapter.out.sec;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class Sec13fSubmissionsParser {

    private Sec13fSubmissionsParser() {
    }

    static List<FilingReference> parse(JsonParser parser, String expectedCik, int limit) {
        Objects.requireNonNull(parser, "parser");
        var normalizedExpectedCik = normalizeCik(expectedCik);
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        require(token, JsonToken.START_OBJECT, "SEC submissions root");
        RecentArrays recent = null;
        String responseCik = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            require(token, JsonToken.PROPERTY_NAME, "SEC submissions root");
            var name = parser.currentName();
            var value = parser.nextToken();
            if ("filings".equals(name) && value == JsonToken.START_OBJECT) recent = filings(parser);
            else if ("cik".equals(name) && (value == JsonToken.VALUE_STRING || value.isNumeric())) {
                responseCik = parser.getValueAsString();
            }
            else parser.skipChildren();
        }
        if (responseCik == null || !normalizeCik(responseCik).equals(normalizedExpectedCik)) {
            throw new IllegalArgumentException("SEC 13F submissions CIK did not match the requested manager");
        }
        if (recent == null) return List.of();
        var size = recent.forms().size();
        var result = new ArrayList<FilingReference>();
        var periods = new java.util.LinkedHashSet<LocalDate>();
        for (var index = 0; index < size && result.size() < limit; index++) {
            var form = at(recent.forms(), index);
            if (!"13F-HR".equals(form) && !"13F-HR/A".equals(form)) continue;
            var accession = at(recent.accessions(), index);
            var filed = date(at(recent.filingDates(), index));
            var report = date(at(recent.reportDates(), index));
            if (accession == null || filed == null || report == null) continue;
            if (!periods.add(report)) continue;
            result.add(new FilingReference(accession, filed, report));
        }
        return List.copyOf(result);
    }

    private static RecentArrays filings(JsonParser parser) {
        JsonToken token;
        RecentArrays result = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            require(token, JsonToken.PROPERTY_NAME, "SEC filings");
            var name = parser.currentName();
            var value = parser.nextToken();
            if ("recent".equals(name) && value == JsonToken.START_OBJECT) result = recent(parser);
            else parser.skipChildren();
        }
        return result;
    }

    private static RecentArrays recent(JsonParser parser) {
        var forms = List.<String>of();
        var accessions = List.<String>of();
        var filingDates = List.<String>of();
        var reportDates = List.<String>of();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            require(token, JsonToken.PROPERTY_NAME, "SEC recent filings");
            var name = parser.currentName();
            var value = parser.nextToken();
            if (value != JsonToken.START_ARRAY) {
                parser.skipChildren();
                continue;
            }
            switch (name) {
                case "form" -> forms = strings(parser);
                case "accessionNumber" -> accessions = strings(parser);
                case "filingDate" -> filingDates = strings(parser);
                case "reportDate" -> reportDates = strings(parser);
                default -> parser.skipChildren();
            }
        }
        return new RecentArrays(forms, accessions, filingDates, reportDates);
    }

    private static List<String> strings(JsonParser parser) {
        var result = new ArrayList<String>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            result.add(token == JsonToken.VALUE_STRING ? parser.getString() : "");
            parser.skipChildren();
        }
        return List.copyOf(result);
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static LocalDate date(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void require(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private static String normalizeCik(String value) {
        if (value == null) throw new IllegalArgumentException("CIK is required");
        var digits = value.replaceAll("\\D+", "");
        if (digits.isBlank() || digits.length() > 10) throw new IllegalArgumentException("CIK is invalid");
        return "0".repeat(10 - digits.length()) + digits;
    }

    record FilingReference(String accessionNumber, LocalDate filedOn, LocalDate reportPeriod) {
    }

    private record RecentArrays(
            List<String> forms,
            List<String> accessions,
            List<String> filingDates,
            List<String> reportDates
    ) {
    }
}
