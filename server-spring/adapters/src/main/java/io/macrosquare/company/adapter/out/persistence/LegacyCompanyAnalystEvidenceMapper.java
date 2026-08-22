package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Streaming projection of the legacy source-cache envelopes. */
final class LegacyCompanyAnalystEvidenceMapper {

    private LegacyCompanyAnalystEvidenceMapper() {
    }

    static CurrentConsensus mapConsensus(
            JsonParser parser,
            String ticker,
            Instant now,
            Duration staleTtl
    ) {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(staleTtl, "staleTtl");
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        requireToken(token, JsonToken.START_OBJECT, "analyst consensus envelope");

        Instant updatedAt = null;
        CurrentConsensus current = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "analyst consensus envelope");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            switch (field) {
                case "updatedAt" -> updatedAt = Instant.parse(requiredText(parser, valueToken, "updatedAt"));
                case "value" -> current = parseConsensusValue(parser, valueToken, ticker);
                default -> parser.skipChildren();
            }
        }
        if (updatedAt == null || current == null) {
            throw new IllegalArgumentException("analyst consensus envelope is incomplete");
        }
        if (updatedAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            return new CurrentConsensus(null, null);
        }
        if (now.isAfter(updatedAt.plus(staleTtl))) return new CurrentConsensus(null, null);
        return current;
    }

    static List<CompanyAnalystHistoryPoint> mapHistory(JsonParser parser) {
        Objects.requireNonNull(parser, "parser");
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        requireToken(token, JsonToken.START_OBJECT, "analyst history envelope");

        List<CompanyAnalystHistoryPoint> history = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "analyst history envelope");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            if ("value".equals(field)) history = parseHistoryArray(parser, valueToken);
            else parser.skipChildren();
        }
        if (history == null) throw new IllegalArgumentException("analyst history envelope has no value");
        return List.copyOf(history);
    }

    private static CurrentConsensus parseConsensusValue(JsonParser parser, JsonToken token, String ticker) {
        requireToken(token, JsonToken.START_OBJECT, "analyst consensus value");
        Double analystScore = null;
        Double upsidePct = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "analyst consensus value");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            switch (field) {
                case "perTicker" -> analystScore = parseTickerValue(parser, valueToken, ticker, "perTicker");
                case "perTickerUpsidePct" -> upsidePct = parseTickerValue(
                        parser, valueToken, ticker, "perTickerUpsidePct"
                );
                default -> parser.skipChildren();
            }
        }
        return new CurrentConsensus(analystScore, upsidePct);
    }

    private static Double parseTickerValue(
            JsonParser parser,
            JsonToken token,
            String ticker,
            String context
    ) {
        if (token == JsonToken.VALUE_NULL) return null;
        requireToken(token, JsonToken.START_OBJECT, context);
        Double matched = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, context);
            var key = parser.currentName();
            var valueToken = parser.nextToken();
            if (ticker.equals(key)) matched = nullableNumber(parser, valueToken, context + "." + ticker);
            else parser.skipChildren();
        }
        return matched;
    }

    private static List<CompanyAnalystHistoryPoint> parseHistoryArray(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_ARRAY, "analyst history value");
        var history = new ArrayList<CompanyAnalystHistoryPoint>();
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) throw new IllegalArgumentException("analyst history ended unexpectedly");
            history.add(parseHistoryPoint(parser, token));
        }
        return history;
    }

    private static CompanyAnalystHistoryPoint parseHistoryPoint(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_OBJECT, "analyst history point");
        LocalDate date = null;
        Double analystScore = null;
        Double upsidePct = null;
        Double epsRevision7dPct = null;
        Double epsRevision30dPct = null;
        Double epsRevision90dPct = null;
        var analystScoreSeen = false;
        var upsideSeen = false;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "analyst history point");
            var field = parser.currentName();
            var valueToken = parser.nextToken();
            switch (field) {
                case "date" -> date = LocalDate.parse(requiredText(parser, valueToken, "history.date"));
                case "analystScore" -> {
                    analystScore = nullableNumber(parser, valueToken, "history.analystScore");
                    analystScoreSeen = true;
                }
                case "upsidePct" -> {
                    upsidePct = nullableNumber(parser, valueToken, "history.upsidePct");
                    upsideSeen = true;
                }
                case "epsEstimateRevision7dPct" -> epsRevision7dPct = nullableNumber(
                        parser, valueToken, "history.epsEstimateRevision7dPct");
                case "epsEstimateRevision30dPct" -> epsRevision30dPct = nullableNumber(
                        parser, valueToken, "history.epsEstimateRevision30dPct");
                case "epsEstimateRevision90dPct" -> epsRevision90dPct = nullableNumber(
                        parser, valueToken, "history.epsEstimateRevision90dPct");
                default -> parser.skipChildren();
            }
        }
        if (date == null || !analystScoreSeen || !upsideSeen) {
            throw new IllegalArgumentException("analyst history point is incomplete");
        }
        return new CompanyAnalystHistoryPoint(
                date, analystScore, upsidePct,
                epsRevision7dPct, epsRevision30dPct, epsRevision90dPct);
    }

    private static String requiredText(JsonParser parser, JsonToken token, String field) {
        if (token != JsonToken.VALUE_STRING) throw new IllegalArgumentException(field + " must be text");
        var value = parser.getString();
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static Double nullableNumber(JsonParser parser, JsonToken token, String field) {
        if (token == JsonToken.VALUE_NULL) return null;
        if (token == null || !token.isNumeric()) {
            throw new IllegalArgumentException(field + " must be numeric or null");
        }
        var value = parser.getDoubleValue();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
        return value;
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    record CurrentConsensus(Double analystScore, Double upsidePct) {
    }
}
