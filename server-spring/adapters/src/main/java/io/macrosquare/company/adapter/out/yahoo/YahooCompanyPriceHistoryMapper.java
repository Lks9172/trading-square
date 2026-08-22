package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Streaming projection of Yahoo timestamps, daily closes, and volumes. */
final class YahooCompanyPriceHistoryMapper {

    /**
     * A split event is explicit provider metadata, but the event alone does
     * not prove that Yahoo has not already revised the history. Require the
     * adjacent close discontinuity to be close to the announced ratio before
     * applying a local basis normalization. The deliberately narrow tolerance
     * avoids treating an ordinary large overnight move as a pending split.
     */
    private static final double PENDING_SPLIT_RATIO_TOLERANCE = 0.15;

    private YahooCompanyPriceHistoryMapper() {
    }

    static List<BottomPatternPoint> map(JsonParser parser, String expectedSourceSymbol) {
        Objects.requireNonNull(parser, "parser");
        var expected = normalizeSymbol(expectedSourceSymbol);
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        requireToken(token, JsonToken.START_OBJECT, "Yahoo chart response");

        List<BottomPatternPoint> history = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart response");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("chart".equals(name)) history = parseChart(parser, valueToken, expected);
            else parser.skipChildren();
        }
        if (history == null || history.isEmpty()) {
            throw new IllegalArgumentException("Yahoo chart response did not contain price history");
        }
        return history;
    }

    private static List<BottomPatternPoint> parseChart(
            JsonParser parser,
            JsonToken token,
            String expectedSourceSymbol
    ) {
        requireToken(token, JsonToken.START_OBJECT, "Yahoo chart");
        List<BottomPatternPoint> history = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("result".equals(name)) history = parseFirstResult(parser, valueToken, expectedSourceSymbol);
            else parser.skipChildren();
        }
        return history;
    }

    private static List<BottomPatternPoint> parseFirstResult(
            JsonParser parser,
            JsonToken token,
            String expectedSourceSymbol
    ) {
        if (token == JsonToken.VALUE_NULL) return null;
        requireToken(token, JsonToken.START_ARRAY, "Yahoo chart result");
        var firstToken = parser.nextToken();
        if (firstToken == JsonToken.END_ARRAY) return null;
        if (firstToken != JsonToken.START_OBJECT) {
            parser.skipChildren();
            skipRemainingArray(parser);
            return null;
        }

        List<Long> timestamps = null;
        List<Double> closes = null;
        List<Double> volumes = null;
        List<Double> highs = null;
        List<Double> lows = null;
        List<SplitEvent> splits = List.of();
        String returnedSymbol = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart result entry");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("meta".equals(name)) returnedSymbol = parseMetaSymbol(parser, valueToken);
            else if ("timestamp".equals(name)) timestamps = longArray(parser, valueToken, "timestamp");
            else if ("indicators".equals(name)) {
                var quote = parseIndicators(parser, valueToken);
                closes = quote.closes();
                volumes = quote.volumes();
                highs = quote.highs();
                lows = quote.lows();
            } else if ("events".equals(name)) splits = parseSplitEvents(parser, valueToken);
            else parser.skipChildren();
        }
        skipRemainingArray(parser);
        if (!expectedSourceSymbol.equals(normalizeSymbol(returnedSymbol))) {
            throw new IllegalArgumentException("Yahoo chart symbol did not match the requested security");
        }
        return assemble(timestamps, closes, volumes, highs, lows, splits);
    }

    private static List<SplitEvent> parseSplitEvents(JsonParser parser, JsonToken token) {
        if (token == JsonToken.VALUE_NULL) return List.of();
        requireToken(token, JsonToken.START_OBJECT, "Yahoo events");
        var splits = new ArrayList<SplitEvent>();
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo events");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("splits".equals(name)) parseSplits(parser, valueToken, splits);
            else parser.skipChildren();
        }
        splits.sort(java.util.Comparator.comparingLong(SplitEvent::epochSecond));
        return List.copyOf(splits);
    }

    private static void parseSplits(JsonParser parser, JsonToken token, List<SplitEvent> splits) {
        if (token == JsonToken.VALUE_NULL) return;
        requireToken(token, JsonToken.START_OBJECT, "Yahoo split events");
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo split event key");
            parser.nextToken();
            var event = parseSplit(parser);
            if (event != null) splits.add(event);
        }
    }

    private static SplitEvent parseSplit(JsonParser parser) {
        requireToken(parser.currentToken(), JsonToken.START_OBJECT, "Yahoo split event");
        Long epochSecond = null;
        Double numerator = null;
        Double denominator = null;
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo split event");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("date".equals(name) && valueToken != null && valueToken.isNumeric()) {
                epochSecond = parser.getLongValue();
            } else if ("numerator".equals(name) && valueToken != null && valueToken.isNumeric()) {
                numerator = parser.getDoubleValue();
            } else if ("denominator".equals(name) && valueToken != null && valueToken.isNumeric()) {
                denominator = parser.getDoubleValue();
            } else {
                parser.skipChildren();
            }
        }
        if (epochSecond == null || epochSecond <= 0 || numerator == null || denominator == null
                || !Double.isFinite(numerator) || !Double.isFinite(denominator)
                || numerator <= 0 || denominator <= 0) return null;
        return new SplitEvent(epochSecond, numerator / denominator);
    }

    private static String parseMetaSymbol(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_OBJECT, "Yahoo chart meta");
        String symbol = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo chart meta");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("symbol".equals(name) && valueToken == JsonToken.VALUE_STRING) {
                symbol = parser.getValueAsString();
            } else {
                parser.skipChildren();
            }
        }
        return symbol;
    }

    private static QuoteArrays parseIndicators(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_OBJECT, "Yahoo indicators");
        QuoteArrays quote = QuoteArrays.EMPTY;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo indicators");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("quote".equals(name)) quote = parseFirstQuote(parser, valueToken);
            else parser.skipChildren();
        }
        return quote;
    }

    private static QuoteArrays parseFirstQuote(JsonParser parser, JsonToken token) {
        requireToken(token, JsonToken.START_ARRAY, "Yahoo quote indicators");
        var firstToken = parser.nextToken();
        if (firstToken == JsonToken.END_ARRAY) return QuoteArrays.EMPTY;
        if (firstToken != JsonToken.START_OBJECT) {
            parser.skipChildren();
            skipRemainingArray(parser);
            return QuoteArrays.EMPTY;
        }
        List<Double> closes = null;
        List<Double> volumes = null;
        List<Double> highs = null;
        List<Double> lows = null;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            requireToken(token, JsonToken.PROPERTY_NAME, "Yahoo quote indicator");
            var name = parser.currentName();
            var valueToken = parser.nextToken();
            if ("close".equals(name)) closes = doubleArray(parser, valueToken, "close");
            else if ("volume".equals(name)) volumes = doubleArray(parser, valueToken, "volume");
            else if ("high".equals(name)) highs = doubleArray(parser, valueToken, "high");
            else if ("low".equals(name)) lows = doubleArray(parser, valueToken, "low");
            else parser.skipChildren();
        }
        skipRemainingArray(parser);
        return new QuoteArrays(closes, volumes, highs, lows);
    }

    private static List<BottomPatternPoint> assemble(
            List<Long> timestamps,
            List<Double> closes,
            List<Double> volumes,
            List<Double> highs,
            List<Double> lows,
            List<SplitEvent> splits
    ) {
        if (timestamps == null || closes == null) return null;
        var pendingAdjustments = pendingSplitAdjustments(timestamps, closes, splits);
        var history = new ArrayList<BottomPatternPoint>(timestamps.size());
        for (var index = 0; index < timestamps.size(); index++) {
            var timestamp = timestamps.get(index);
            var close = index < closes.size() ? closes.get(index) : null;
            if (timestamp == null || timestamp <= 0 || close == null || !Double.isFinite(close) || close <= 0) {
                continue;
            }
            var adjustment = splitAdjustmentAfter(timestamp, pendingAdjustments);
            close /= adjustment;
            var volume = volumes != null && index < volumes.size() ? volumes.get(index) : null;
            // Missing/zero volume is unavailable evidence, not zero trading.
            // Manufacturing zero here can make a later normal-volume day look
            // like capitulation and create a false bottom/reversal signal.
            if (volume == null || !Double.isFinite(volume) || volume <= 0) continue;
            volume *= adjustment;
            var high = finitePositiveAt(highs, index);
            var low = finitePositiveAt(lows, index);
            if (high != null) high /= adjustment;
            if (low != null) low /= adjustment;
            if (high != null && low != null && high < low) {
                high = null;
                low = null;
            }
            try {
                var date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
                history.add(new BottomPatternPoint(date, close, volume, high, low));
            } catch (DateTimeException error) {
                throw new IllegalArgumentException("Yahoo timestamp is out of range", error);
            }
        }
        return List.copyOf(history);
    }

    /**
     * Yahoo normally revises historical OHLCV to the latest split basis, but a
     * newly effective split can temporarily arrive as an event while pre-split
     * candles remain unrevised. Adjust only when the close immediately before
     * and after the event still exhibits the announced ratio; otherwise the
     * provider history is already adjusted and must not be divided twice.
     */
    private static List<SplitEvent> pendingSplitAdjustments(
            List<Long> timestamps,
            List<Double> closes,
            List<SplitEvent> splits
    ) {
        if (splits == null || splits.isEmpty()) return List.of();
        var pending = new ArrayList<SplitEvent>();
        for (var split : splits) {
            Double before = null;
            Double after = null;
            for (var index = 0; index < timestamps.size(); index++) {
                var timestamp = timestamps.get(index);
                var close = index < closes.size() ? closes.get(index) : null;
                if (timestamp == null || close == null || !Double.isFinite(close) || close <= 0) continue;
                if (timestamp < split.epochSecond()) before = close;
                else if (after == null) after = close;
            }
            if (before == null || after == null) continue;
            var observedRatio = before / after;
            if (relativeDistance(observedRatio, split.ratio()) <= PENDING_SPLIT_RATIO_TOLERANCE) {
                pending.add(split);
            }
        }
        return List.copyOf(pending);
    }

    private static double splitAdjustmentAfter(long timestamp, List<SplitEvent> splits) {
        var adjustment = 1d;
        for (var split : splits) {
            if (timestamp < split.epochSecond()) adjustment *= split.ratio();
        }
        return adjustment;
    }

    private static double relativeDistance(double value, double expected) {
        return Math.abs(value / expected - 1d);
    }

    private static Double finitePositiveAt(List<Double> values, int index) {
        if (values == null || index >= values.size()) return null;
        var value = values.get(index);
        return value != null && Double.isFinite(value) && value > 0 ? value : null;
    }

    private static List<Long> longArray(JsonParser parser, JsonToken token, String field) {
        requireToken(token, JsonToken.START_ARRAY, "Yahoo " + field);
        var values = new ArrayList<Long>();
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == JsonToken.VALUE_NULL) values.add(null);
            else if (token != null && token.isNumeric()) values.add(parser.getLongValue());
            else throw new IllegalArgumentException("Yahoo " + field + " must contain numbers or null");
        }
        return values;
    }

    private static List<Double> doubleArray(JsonParser parser, JsonToken token, String field) {
        requireToken(token, JsonToken.START_ARRAY, "Yahoo " + field);
        var values = new ArrayList<Double>();
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == JsonToken.VALUE_NULL) values.add(null);
            else if (token != null && token.isNumeric()) values.add(parser.getDoubleValue());
            else throw new IllegalArgumentException("Yahoo " + field + " must contain numbers or null");
        }
        return values;
    }

    private static void skipRemainingArray(JsonParser parser) {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) throw new IllegalArgumentException("Yahoo array ended unexpectedly");
            parser.skipChildren();
        }
    }

    private static void requireToken(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private static String normalizeSymbol(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private record QuoteArrays(
            List<Double> closes,
            List<Double> volumes,
            List<Double> highs,
            List<Double> lows
    ) {
        private static final QuoteArrays EMPTY = new QuoteArrays(null, null, null, null);
    }

    private record SplitEvent(long epochSecond, double ratio) {
    }
}
