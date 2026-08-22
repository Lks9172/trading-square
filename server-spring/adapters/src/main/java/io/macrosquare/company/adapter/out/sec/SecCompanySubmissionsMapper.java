package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Streaming parser for the compact columnar SEC submissions payload. */
final class SecCompanySubmissionsMapper {

    private static final String ARCHIVE_BASE_URL = "https://www.sec.gov/Archives/edgar/data";
    private static final int MAX_RECENT_ROWS_TO_SCAN = 20_000;
    private static final java.util.Set<String> PERIODIC_FORMS = java.util.Set.of(
            "10-Q", "10-K", "20-F", "40-F"
    );

    private SecCompanySubmissionsMapper() {
    }

    static CompanySubmissionsEvidence map(JsonParser parser, String requestedCik, int filingLimit) {
        Objects.requireNonNull(parser, "parser");
        var normalizedRequestedCik = normalizeCik(requestedCik);
        if (filingLimit < 1) throw new IllegalArgumentException("filingLimit must be positive");
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        if (token != JsonToken.START_OBJECT) {
            throw new IllegalArgumentException("SEC submissions response must be an object");
        }

        String responseCik = null;
        String name = null;
        String sic = null;
        var tickers = List.<String>of();
        var exchanges = List.<String>of();
        RecentColumns recent = RecentColumns.empty();
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC submissions response");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            switch (field) {
                case "cik" -> responseCik = scalarText(valueToken, parser, "cik");
                case "name" -> name = nullableText(valueToken, parser, "name");
                case "sic" -> sic = nullableText(valueToken, parser, "sic");
                case "tickers" -> tickers = textArray(valueToken, parser, "tickers");
                case "exchanges" -> exchanges = textArray(valueToken, parser, "exchanges");
                case "filings" -> recent = filings(valueToken, parser, filingLimit);
                default -> parser.skipChildren();
            }
        }

        if (responseCik != null && !normalizeCik(responseCik).equals(normalizedRequestedCik)) {
            throw new IllegalArgumentException("SEC submissions CIK did not match the requested CIK");
        }
        var filings = normalizeFilings(normalizedRequestedCik, recent, filingLimit);
        return new CompanySubmissionsEvidence(
                normalizedRequestedCik,
                name == null ? normalizedRequestedCik : name,
                tickers,
                exchanges,
                sic,
                filings
        );
    }

    private static RecentColumns filings(JsonToken token, JsonParser parser, int limit) {
        if (token == JsonToken.VALUE_NULL) return RecentColumns.empty();
        requireToken(token, JsonToken.START_OBJECT, "SEC filings");
        var recent = RecentColumns.empty();
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC filings");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            if ("recent".equals(field)) recent = recent(valueToken, parser, limit);
            else parser.skipChildren();
        }
        return recent;
    }

    private static RecentColumns recent(JsonToken token, JsonParser parser, int limit) {
        if (token == JsonToken.VALUE_NULL) return RecentColumns.empty();
        requireToken(token, JsonToken.START_OBJECT, "SEC recent filings");
        var columns = new RecentColumnsBuilder();
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "SEC recent filings");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            switch (field) {
                case "accessionNumber" -> columns.accessionNumbers = nullableTextColumn(valueToken, parser, field, limit);
                case "filingDate" -> columns.filingDates = nullableTextColumn(valueToken, parser, field, limit);
                case "reportDate" -> columns.reportDates = nullableTextColumn(valueToken, parser, field, limit);
                case "form" -> columns.forms = nullableTextColumn(valueToken, parser, field, limit);
                case "primaryDocument" -> columns.primaryDocuments = nullableTextColumn(valueToken, parser, field, limit);
                case "primaryDocDescription" -> columns.primaryDocumentDescriptions =
                        nullableTextColumn(valueToken, parser, field, limit);
                case "items" -> columns.items = nullableTextColumn(valueToken, parser, field, limit);
                default -> parser.skipChildren();
            }
        }
        return columns.build();
    }

    private static List<CompanyFilingEvidence> normalizeFilings(
            String cik,
            RecentColumns columns,
            int limit
    ) {
        var result = new ArrayList<CompanyFilingEvidence>();
        var formCount = columns.forms().size();
        for (var index = 0; index < formCount; index++) {
            var accession = at(columns.accessionNumbers(), index);
            var date = at(columns.filingDates(), index);
            var form = at(columns.forms(), index);
            if (isEmpty(accession) || isEmpty(date) || isEmpty(form)) continue;
            // Keep the bounded recent public projection, but always retain
            // periodic filings discovered deeper in the SEC one-year array.
            // Large banks can file thousands of 424B2s between two 10-Qs; a
            // simple first-N truncation silently hid the newest financial
            // statement and made stale Company Facts look current.
            if (index >= limit && !PERIODIC_FORMS.contains(form)) continue;
            var primaryDocument = at(columns.primaryDocuments(), index);
            var description = at(columns.primaryDocumentDescriptions(), index);
            var items = at(columns.items(), index);
            final LocalDate filingDate;
            try {
                filingDate = LocalDate.parse(date);
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException("SEC filingDate must be an ISO date", error);
            }
            final LocalDate reportDate;
            try {
                var rawReportDate = at(columns.reportDates(), index);
                reportDate = isEmpty(rawReportDate) ? null : LocalDate.parse(rawReportDate);
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException("SEC reportDate must be an ISO date", error);
            }
            result.add(new CompanyFilingEvidence(
                    accession,
                    filingDate,
                    reportDate,
                    form,
                    primaryDocument,
                    description,
                    items,
                    filingUrl(cik, accession, primaryDocument)
            ));
        }
        return List.copyOf(result);
    }

    private static String filingUrl(String cik, String accession, String primaryDocument) {
        if (primaryDocument == null || primaryDocument.isEmpty()) return null;
        return ARCHIVE_BASE_URL + "/" + Long.parseLong(cik) + "/"
                + accession.replace("-", "") + "/" + primaryDocument;
    }

    private static List<String> textArray(JsonToken token, JsonParser parser, String field) {
        if (token == JsonToken.VALUE_NULL) return List.of();
        requireToken(token, JsonToken.START_ARRAY, "SEC " + field);
        var values = new ArrayList<String>();
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == JsonToken.VALUE_NULL) continue;
            if (token != JsonToken.VALUE_STRING) {
                throw new IllegalArgumentException("SEC " + field + " must contain text only");
            }
            values.add(parser.getString());
        }
        return List.copyOf(values);
    }

    private static List<String> nullableTextColumn(
            JsonToken token,
            JsonParser parser,
            String field,
            int limit
    ) {
        if (token == JsonToken.VALUE_NULL) return List.of();
        requireToken(token, JsonToken.START_ARRAY, "SEC " + field);
        var values = new ArrayList<String>(Math.min(limit, MAX_RECENT_ROWS_TO_SCAN));
        var index = 0;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (index < MAX_RECENT_ROWS_TO_SCAN) {
                if (token == JsonToken.VALUE_NULL) values.add(null);
                else if (token == JsonToken.VALUE_STRING) values.add(parser.getString());
                else throw new IllegalArgumentException("SEC " + field + " must contain text or null only");
            }
            index++;
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) parser.skipChildren();
        }
        return values;
    }

    private static String scalarText(JsonToken token, JsonParser parser, String field) {
        if (token == JsonToken.VALUE_STRING) return parser.getString();
        if (token != null && token.isNumeric()) return parser.getNumberValue().toString();
        throw new IllegalArgumentException("SEC " + field + " must be text or numeric");
    }

    private static String nullableText(JsonToken token, JsonParser parser, String field) {
        if (token == JsonToken.VALUE_NULL) return null;
        return scalarText(token, parser, field);
    }

    private static String at(List<String> values, int index) {
        return index < values.size() ? values.get(index) : null;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static String normalizeCik(String cik) {
        if (cik == null) throw new IllegalArgumentException("cik is required");
        var digits = cik.replaceAll("\\D+", "");
        if (digits.isEmpty() || digits.length() > 10) throw new IllegalArgumentException("invalid CIK");
        return "0".repeat(10 - digits.length()) + digits;
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private record RecentColumns(
            List<String> accessionNumbers,
            List<String> filingDates,
            List<String> reportDates,
            List<String> forms,
            List<String> primaryDocuments,
            List<String> primaryDocumentDescriptions,
            List<String> items
    ) {
        static RecentColumns empty() {
            return new RecentColumns(
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
            );
        }
    }

    private static final class RecentColumnsBuilder {
        private List<String> accessionNumbers = List.of();
        private List<String> filingDates = List.of();
        private List<String> reportDates = List.of();
        private List<String> forms = List.of();
        private List<String> primaryDocuments = List.of();
        private List<String> primaryDocumentDescriptions = List.of();
        private List<String> items = List.of();

        RecentColumns build() {
            return new RecentColumns(
                    accessionNumbers,
                    filingDates,
                    reportDates,
                    forms,
                    primaryDocuments,
                    primaryDocumentDescriptions,
                    items
            );
        }
    }
}
