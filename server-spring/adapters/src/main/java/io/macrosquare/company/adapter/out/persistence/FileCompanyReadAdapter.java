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
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads crash-safe company projections from the shared source-cache mount.
 *
 * <p>The legacy directory is mounted read-only during migration. This adapter
 * validates every envelope and preserves every available public field. During
 * final cutover a lite projection is an explicit last-valid seed for companies
 * that were not opened before Node retirement; missing enrichments remain null
 * instead of making the whole company page unavailable.</p>
 */
public final class FileCompanyReadAdapter implements LoadCompanyReadPort {

    private static final String DIRECTORY_FILE = "sec-company-ticker-map.json";

    private final ObjectMapper objectMapper;
    private final Path sourceCacheDirectory;
    private final long maximumFileBytes;
    private final int maximumCachedFiles;
    private final ConcurrentHashMap<Path, CachedJson> jsonCache = new ConcurrentHashMap<>();
    private volatile CachedDirectory directoryCache;

    public FileCompanyReadAdapter(
            ObjectMapper objectMapper,
            Path sourceCacheDirectory,
            long maximumFileBytes,
            int maximumCachedFiles
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.sourceCacheDirectory = absolute(sourceCacheDirectory, "sourceCacheDirectory");
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        if (maximumCachedFiles <= 0) throw new IllegalArgumentException("maximumCachedFiles must be positive");
        this.maximumFileBytes = maximumFileBytes;
        this.maximumCachedFiles = maximumCachedFiles;
    }

    @Override
    public SearchResult search(String normalizedQuery, int limit) {
        try {
            var query = normalizedQuery.toUpperCase(Locale.ROOT);
            var matches = directory().values().stream()
                    .filter(item -> item.ticker().contains(query)
                            || item.title().toUpperCase(Locale.ROOT).contains(query))
                    .sorted(Comparator
                            .comparingInt((SearchItem item) -> matchRank(item.ticker(), query))
                            .thenComparing(SearchItem::ticker))
                    .limit(limit)
                    .toList();
            return new SearchResult(matches);
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
            if (identity == null) {
                throw new CompanyTickerNotFoundException(ticker);
            }
            var value = readFirstExisting(
                    "route_company-detail_v1_" + storageTicker(ticker).toLowerCase(Locale.ROOT) + ".json",
                    "company-research-full-" + storageTicker(ticker).toLowerCase(Locale.ROOT) + ".json",
                    "company-research-lite-" + storageTicker(ticker).toLowerCase(Locale.ROOT) + ".json"
            );
            if (value == null) {
                if (CurrentResearchUniverseTickerRegistry.isReplacementTicker(ticker)) {
                    return CurrentCompanyResearchSeedFactory.identityOnly(identity);
                }
                throw new CompanyReadUnavailableException(
                        "A complete persisted company detail is unavailable for " + ticker);
            }
            return CompanyReadJsonMapper.mapResearch(normalizeCurrentTickerProjection(value, ticker));
        } catch (CompanyTickerNotFoundException | CompanyReadUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("Unable to load the persisted company detail for " + ticker, error);
        }
    }

    private Map<String, SearchItem> directory() {
        try {
            var path = resolve(DIRECTORY_FILE);
            var stamp = stamp(path);
            var current = directoryCache;
            if (current != null && current.stamp().equals(stamp)) return current.items();

            var root = json(path, stamp);
            var value = requiredObject(root, "value");
            var items = new LinkedHashMap<String, SearchItem>(value.size());
            value.properties().forEach(entry -> {
                var node = entry.getValue();
                var ticker = canonicalTicker(requiredText(node, "ticker"));
                if (CurrentResearchUniverseTickerRegistry.retired(ticker)) return;
                items.put(ticker, new SearchItem(
                        ticker,
                        requiredText(node, "cik"),
                        requiredText(node, "title")
                ));
            });
            if (items.isEmpty()) throw new IllegalArgumentException("persisted SEC directory is empty");
            var loaded = new CachedDirectory(stamp, Map.copyOf(items));
            directoryCache = loaded;
            return loaded.items();
        } catch (CompanyReadUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Unable to load the persisted SEC ticker directory", error);
        }
    }

    private JsonNode readLiteOrFull(String ticker) {
        var lower = storageTicker(ticker).toLowerCase(Locale.ROOT);
        return readFirstExisting(
                "company-research-lite-" + lower + ".json",
                "company-research-full-" + lower + ".json"
        );
    }

    private JsonNode readFirstExisting(String... fileNames) {
        for (var fileName : fileNames) {
            var path = resolve(fileName);
            if (!Files.isRegularFile(path)) continue;
            try {
                var root = json(path, stamp(path));
                return requiredObject(root, "value");
            } catch (IOException error) {
                throw unavailable("Unable to read persisted company file " + fileName, error);
            }
        }
        return null;
    }

    private Summary summary(JsonNode research, String requestedTicker) {
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

    private JsonNode json(Path path, FileStamp stamp) throws IOException {
        var cached = jsonCache.get(path);
        if (cached != null && cached.stamp().equals(stamp)) return cached.root();
        var bytes = Files.readAllBytes(path);
        if (bytes.length > maximumFileBytes) {
            throw new IllegalArgumentException("company source-cache file exceeds its configured bound");
        }
        var root = objectMapper.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("company source-cache envelope must be an object");
        }
        var loaded = new CachedJson(stamp, root, System.nanoTime());
        jsonCache.put(path, loaded);
        evictOldest();
        return root;
    }

    private void evictOldest() {
        while (jsonCache.size() > maximumCachedFiles) {
            var oldest = jsonCache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().loadedSequence()))
                    .orElse(null);
            if (oldest == null) return;
            jsonCache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private FileStamp stamp(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("company source-cache file does not exist");
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.size() > maximumFileBytes) {
            throw new IllegalArgumentException("company source-cache file exceeds its configured bound");
        }
        return new FileStamp(attributes.lastModifiedTime().toMillis(), attributes.size());
    }

    private Path resolve(String fileName) {
        if (!fileName.matches("[A-Za-z0-9._-]+\\.json")) {
            throw new IllegalArgumentException("invalid company source-cache file name");
        }
        var path = sourceCacheDirectory.resolve(fileName).normalize();
        if (!path.startsWith(sourceCacheDirectory)) {
            throw new IllegalArgumentException("company source-cache path escapes its directory");
        }
        return path;
    }

    private static int matchRank(String ticker, String query) {
        if (ticker.equals(query)) return -2;
        if (ticker.startsWith(query)) return -1;
        return 0;
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
        return BigDecimal.valueOf(value.asDouble());
    }

    private static Path absolute(Path value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value.normalize();
    }

    private static CompanyReadUnavailableException unavailable(String message, Throwable cause) {
        return new CompanyReadUnavailableException(message, cause);
    }

    private record FileStamp(long modifiedAtMillis, long size) {
    }

    private record CachedJson(FileStamp stamp, JsonNode root, long loadedSequence) {
    }

    private record CachedDirectory(FileStamp stamp, Map<String, SearchItem> items) {
    }
}
