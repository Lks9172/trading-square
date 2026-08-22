package io.macrosquare.institutional.adapter.out.sec;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Sec13fIndexParser {

    private Sec13fIndexParser() {
    }

    static List<String> xmlCandidates(JsonParser parser) {
        var token = parser.currentToken() == null ? parser.nextToken() : parser.currentToken();
        require(token, JsonToken.START_OBJECT, "SEC archive index");
        var candidates = new ArrayList<Candidate>();
        scanObject(parser, candidates);
        return candidates.stream()
                .filter(value -> value.name().toLowerCase(java.util.Locale.ROOT).endsWith(".xml"))
                .filter(value -> !value.name().toLowerCase(java.util.Locale.ROOT).contains("primary_doc"))
                .sorted(Comparator.comparingLong(Candidate::size).reversed())
                .map(Candidate::name)
                .distinct()
                .toList();
    }

    private static void scanObject(JsonParser parser, List<Candidate> candidates) {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            require(token, JsonToken.PROPERTY_NAME, "SEC archive index object");
            var name = parser.currentName();
            var value = parser.nextToken();
            if ("item".equals(name) && value == JsonToken.START_ARRAY) items(parser, candidates);
            else if (value == JsonToken.START_OBJECT) scanObject(parser, candidates);
            else parser.skipChildren();
        }
    }

    private static void items(JsonParser parser, List<Candidate> candidates) {
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String name = null;
            long size = 0;
            while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
                require(token, JsonToken.PROPERTY_NAME, "SEC archive item");
                var field = parser.currentName();
                var value = parser.nextToken();
                if ("name".equals(field) && value == JsonToken.VALUE_STRING) name = parser.getString();
                else if ("size".equals(field)) size = longValue(parser, value);
                parser.skipChildren();
            }
            if (name != null && !name.isBlank()) candidates.add(new Candidate(name, size));
        }
    }

    private static long longValue(JsonParser parser, JsonToken token) {
        if (token != null && token.isNumeric()) return parser.getLongValue();
        if (token == JsonToken.VALUE_STRING) {
            try {
                return Long.parseLong(parser.getString());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static void require(JsonToken actual, JsonToken expected, String context) {
        if (actual != expected) throw new IllegalArgumentException(context + " has an invalid JSON shape");
    }

    private record Candidate(String name, long size) {
    }
}
