package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.adapter.out.json.CompanyReadJsonMapper;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SearchItem;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.Summary;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.port.in.CompanyTickerNotFoundException;
import io.macrosquare.company.application.port.out.CompanyReadUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Reads company projections from the versioned object-store boundary. */
public final class ObjectCompanyReadAdapter implements LoadCompanyReadPort {

    private static final String DIRECTORY_FILE = "sec-company-ticker-map.json";
    private final JsonEnvelopeStore store;

    public ObjectCompanyReadAdapter(JsonEnvelopeStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public SearchResult search(String normalizedQuery, int limit) {
        try {
            var query = normalizedQuery.toUpperCase(Locale.ROOT);
            return new SearchResult(directory().values().stream()
                    .filter(item -> !CurrentResearchUniverseTickerRegistry.retired(item.ticker()))
                    .filter(item -> item.ticker().contains(query)
                            || item.title().toUpperCase(Locale.ROOT).contains(query))
                    .sorted(Comparator
                            .comparingInt((SearchItem item) -> matchRank(item.ticker(), query))
                            .thenComparing(SearchItem::ticker))
                    .limit(limit)
                    .toList());
        } catch (CompanyReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("Unable to search the persisted company directory", error);
        }
    }

    @Override
    public SummaryResult summaries(List<String> normalizedTickers) {
        try {
            var known = directory();
            var summaries = new ArrayList<Summary>();
            for (var ticker : normalizedTickers) {
                var canonical = canonicalTicker(ticker);
                if (CurrentResearchUniverseTickerRegistry.retired(canonical)) continue;
                if (!known.containsKey(canonical.replace('.', '-'))) continue;
                var value = readLiteOrFull(canonical);
                if (value == null) {
                    throw new CompanyReadUnavailableException(
                            "Persisted company summary is unavailable for " + canonical);
                }
                summaries.add(summary(value, canonical));
            }
            return new SummaryResult(summaries);
        } catch (CompanyReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("Unable to load persisted company summaries", error);
        }
    }

    @Override
    public Research detail(String normalizedTicker) {
        var ticker = canonicalTicker(normalizedTicker);
        try {
            if (CurrentResearchUniverseTickerRegistry.retired(ticker)) {
                throw new CompanyTickerNotFoundException(ticker);
            }
            var identity = directory().get(ticker.replace('.', '-'));
            if (identity == null) throw new CompanyTickerNotFoundException(ticker);
            var storageTicker = storageTicker(ticker);
            var value = readFirstExisting(
                    "route_company-detail_v1_" + storageTicker.toLowerCase(Locale.ROOT) + ".json",
                    "company-research-full-" + storageTicker.toLowerCase(Locale.ROOT) + ".json",
                    "company-research-lite-" + storageTicker.toLowerCase(Locale.ROOT) + ".json"
            );
            if (value == null) {
                if (CurrentResearchUniverseTickerRegistry.isReplacementTicker(ticker)) {
                    return CurrentCompanyResearchSeedFactory.identityOnly(identity);
                }
                throw new CompanyReadUnavailableException(
                        "A complete persisted company detail is unavailable for " + ticker);
            }
            var normalized = normalizeCurrentTickerProjection(value, ticker);
            return CompanyReadJsonMapper.mapResearch(normalized);
        } catch (CompanyTickerNotFoundException | CompanyReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("Unable to load the persisted company detail for " + ticker, error);
        }
    }

    private Map<String, SearchItem> directory() {
        var value = store.findValue(DIRECTORY_FILE).orElseThrow(() ->
                new CompanyReadUnavailableException("Persisted SEC ticker directory is unavailable"));
        if (!value.isObject()) throw new CompanyReadUnavailableException("Persisted SEC ticker directory is invalid");
        var items = new LinkedHashMap<String, SearchItem>(value.size());
        value.properties().forEach(entry -> {
            var node = entry.getValue();
            var ticker = canonicalTicker(requiredText(node, "ticker"));
            items.put(ticker, new SearchItem(ticker, requiredText(node, "cik"), requiredText(node, "title")));
        });
        if (items.isEmpty()) throw new CompanyReadUnavailableException("Persisted SEC ticker directory is empty");
        return Map.copyOf(items);
    }

    private JsonNode readLiteOrFull(String ticker) {
        var lower = storageTicker(ticker).toLowerCase(Locale.ROOT);
        return readFirstExisting("company-research-lite-" + lower + ".json",
                "company-research-full-" + lower + ".json");
    }

    private JsonNode readFirstExisting(String... fileNames) {
        for (var fileName : fileNames) {
            var value = store.findValue(fileName);
            if (value.isPresent()) return value.get();
        }
        return null;
    }

    private static Summary summary(JsonNode research, String requestedTicker) {
        var profile = requiredObject(research, "profile");
        var financials = requiredObject(research, "financials");
        var score = requiredObject(research, "score");
        var buyScore = requiredObject(research, "buyScore");
        var bottom = research.get("bottomSignal");
        return new Summary(
                requestedTicker,
                requiredText(profile, "name"),
                nullableInt(score, "totalScore"),
                nullableInt(buyScore, "buyScore"),
                nullableText(buyScore, "label"),
                nullableNumber(financials, "revenueGrowthYoY"),
                nullableNumber(financials, "operatingMargin"),
                nullableNumber(financials, "evToSales"),
                nullableInt(buyScore, "crowdingScore"),
                nullableInt(buyScore, "appealScore"),
                optionalText(bottom, "state"),
                optionalInt(bottom, "earningsBottomScore"),
                optionalInt(bottom, "priceBottomScore"),
                optionalInt(bottom, "volumeConfirmationScore"),
                optionalInt(bottom, "failureRiskScore")
        );
    }

    private static int matchRank(String ticker, String query) {
        if (ticker.equals(query)) return -2;
        return ticker.startsWith(query) ? -1 : 0;
    }

    private static String canonicalTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var normalized = ticker.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9.-]+")) throw new IllegalArgumentException("ticker is invalid");
        return CurrentResearchUniverseTickerRegistry.canonicalTicker(normalized);
    }

    private static String storageTicker(String ticker) {
        return CurrentResearchUniverseTickerRegistry.legacyStorageTicker(ticker);
    }

    /**
     * The immutable cutover artifact retains MMC, while every current boundary
     * must expose MRSH consistently. Normalizing only the profile made the
     * anti-corruption projection reject the document and silently retain stale
     * quote/fundamental values. Keep the compatibility rewrite entirely in the
     * infrastructure adapter rather than teaching the domain about old symbols.
     */
    private static JsonNode normalizeCurrentTickerProjection(JsonNode source, String ticker) {
        var normalized = source.deepCopy();
        if (!(normalized instanceof tools.jackson.databind.node.ObjectNode root)) return normalized;
        putText(root, "profile", "ticker", ticker);
        putText(root, "financials", "ticker", ticker);
        putText(root, "score", "ticker", ticker);
        putText(root, "quote", "symbol", ticker);
        return normalized;
    }

    private static void putText(
            tools.jackson.databind.node.ObjectNode root,
            String objectField,
            String valueField,
            String value
    ) {
        if (root.get(objectField) instanceof tools.jackson.databind.node.ObjectNode object) {
            object.put(valueField, value);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isObject()) throw new IllegalArgumentException(field + " must be an object");
        return value;
    }

    private static String requiredText(JsonNode parent, String field) {
        var value = parent == null ? null : parent.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.stringValue();
    }

    private static Integer nullableInt(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(field + " must be numeric or null");
        var number = value.asDouble();
        if (number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be an integer or null");
        }
        return (int) number;
    }

    private static Integer optionalInt(JsonNode parent, String field) {
        return parent == null || parent.isNull() ? null : nullableInt(parent, field);
    }

    private static String nullableText(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw new IllegalArgumentException(field + " must be text or null");
        return value.stringValue();
    }

    private static String optionalText(JsonNode parent, String field) {
        return parent == null || parent.isNull() ? null : nullableText(parent, field);
    }

    private static BigDecimal nullableNumber(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException(field + " must be numeric or null");
        return value.decimalValue();
    }

    private static CompanyReadUnavailableException unavailable(String message, Throwable cause) {
        return new CompanyReadUnavailableException(message, cause);
    }
}
