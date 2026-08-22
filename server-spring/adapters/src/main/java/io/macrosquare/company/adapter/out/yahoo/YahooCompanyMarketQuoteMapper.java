package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.model.CompanyMarketQuote;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/** Streaming projection of the Yahoo chart response's first result metadata. */
final class YahooCompanyMarketQuoteMapper {

    private YahooCompanyMarketQuoteMapper() {
    }

    static CompanyMarketQuote map(JsonParser parser, String expectedSourceSymbol) {
        Objects.requireNonNull(parser, "parser");
        var expected = normalizeSymbol(expectedSourceSymbol);
        if (expected.isBlank()) throw new IllegalArgumentException("expected Yahoo symbol is required");
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        requireToken(token, JsonToken.START_OBJECT, "Yahoo chart response");

        CompanyMarketQuote quote = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart response");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("chart".equals(name)) quote = parseChart(parser, valueToken);
            else parser.skipChildren();
        }
        if (quote == null || !quote.available()) {
            throw new IllegalArgumentException("Yahoo chart response did not contain an available quote");
        }
        if (!expected.equals(normalizeSymbol(quote.symbol()))) {
            throw new IllegalArgumentException("Yahoo quote symbol did not match the requested security");
        }
        return quote;
    }

    private static CompanyMarketQuote parseChart(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_OBJECT, "Yahoo chart");
        CompanyMarketQuote quote = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("result".equals(name)) quote = parseFirstResult(parser, valueToken);
            else parser.skipChildren();
        }
        return quote;
    }

    private static CompanyMarketQuote parseFirstResult(JsonParser parser, JsonToken token) {
        if (token == JsonToken.VALUE_NULL) return null;
        requireToken(token, JsonToken.START_ARRAY, "Yahoo chart result");
        var firstToken = parser.nextToken();
        if (firstToken == JsonToken.END_ARRAY) return null;
        if (firstToken != JsonToken.START_OBJECT) {
            parser.skipChildren();
            skipRemainingArray(parser);
            return null;
        }

        CompanyMarketQuote quote = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart result entry");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("meta".equals(name)) quote = parseMeta(parser, valueToken);
            else parser.skipChildren();
        }
        skipRemainingArray(parser);
        return quote;
    }

    private static CompanyMarketQuote parseMeta(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_OBJECT, "Yahoo chart metadata");
        String symbol = null;
        Double price = null;
        Long marketTime = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart metadata");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            switch (name) {
                case "symbol" -> symbol = text(parser, valueToken, "symbol");
                case "regularMarketPrice" -> price = decimal(parser, valueToken, "regularMarketPrice");
                case "regularMarketTime" -> marketTime = integer(parser, valueToken, "regularMarketTime");
                default -> parser.skipChildren();
            }
        }
        if (symbol == null || price == null || marketTime == null || marketTime <= 0) {
            throw new IllegalArgumentException("Yahoo chart metadata is incomplete");
        }
        try {
            var date = Instant.ofEpochSecond(marketTime).atZone(ZoneOffset.UTC).toLocalDate();
            return new CompanyMarketQuote(symbol, price, date);
        } catch (DateTimeException error) {
            throw new IllegalArgumentException("Yahoo regularMarketTime is out of range", error);
        }
    }

    private static void skipRemainingArray(JsonParser parser) {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) throw new IllegalArgumentException("Yahoo chart result ended unexpectedly");
            parser.skipChildren();
        }
    }

    private static String text(JsonParser parser, JsonToken token, String field) {
        if (token != JsonToken.VALUE_STRING) throw new IllegalArgumentException("Yahoo " + field + " must be text");
        var value = parser.getString();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Yahoo " + field + " must not be blank");
        return value;
    }

    private static double decimal(JsonParser parser, JsonToken token, String field) {
        if (token == null || !token.isNumeric()) {
            throw new IllegalArgumentException("Yahoo " + field + " must be numeric");
        }
        var value = parser.getDoubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Yahoo " + field + " must be positive and finite");
        }
        return value;
    }

    private static long integer(JsonParser parser, JsonToken token, String field) {
        if (token == null || !token.isNumeric()) {
            throw new IllegalArgumentException("Yahoo " + field + " must be numeric");
        }
        return parser.getLongValue();
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private static String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }
}
