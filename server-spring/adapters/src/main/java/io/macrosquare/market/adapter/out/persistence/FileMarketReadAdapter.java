package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.adapter.out.json.MarketReadJsonMapper;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.market.application.port.out.MarketReadUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native file-backed market read adapter.
 *
 * <p>It reads both the immutable handoff history and the crash-safe files produced
 * by the Spring collector while keeping all filesystem and Jackson concerns inside
 * the outbound adapter. Supporting both on-disk schemas keeps the cutover
 * rollback-safe without leaking persistence details into the application layer.</p>
 */
public final class FileMarketReadAdapter implements LoadMarketReadPort {

    private static final Map<String, Integer> RANGE_DAYS = Map.of(
            "1D", 1,
            "1W", 7,
            "1M", 31,
            "1Y", 366,
            "5Y", 1826
    );
    private static final Map<String, Integer> INTERVAL_STEPS = Map.of(
            "1D", 1,
            "1W", 5,
            "1M", 21
    );
    private static final int MAX_DOCUMENT_CACHE_ENTRIES = 512;
    private static final int MAX_SERIES_CACHE_ENTRIES = 256;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Path snapshotFile;
    private final Path historyDirectory;
    private final long maximumSnapshotBytes;
    private final long maximumHistoryFileBytes;
    private final int maximumHistoryFiles;
    private final ConcurrentHashMap<Path, CachedPoints> pointCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HistoryRequest, CachedDocument> historyDocumentCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SeriesRequest, CachedSeriesDocument> seriesDocumentCache = new ConcurrentHashMap<>();
    private volatile CachedDocument snapshotCache;
    private volatile CachedCoverage coverageCache;

    public FileMarketReadAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            Path snapshotFile,
            Path historyDirectory,
            long maximumSnapshotBytes,
            long maximumHistoryFileBytes,
            int maximumHistoryFiles
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.snapshotFile = absolute(snapshotFile, "snapshotFile");
        this.historyDirectory = absolute(historyDirectory, "historyDirectory");
        this.maximumSnapshotBytes = positive(maximumSnapshotBytes, "maximumSnapshotBytes");
        this.maximumHistoryFileBytes = positive(maximumHistoryFileBytes, "maximumHistoryFileBytes");
        this.maximumHistoryFiles = positive(maximumHistoryFiles, "maximumHistoryFiles");
    }

    @Override
    public Document loadLatestSnapshot() {
        try {
            var stamp = stamp(snapshotFile, maximumSnapshotBytes);
            var cached = snapshotCache;
            if (cached != null && cached.stamp().equals(stamp)) return cached.document();

            var root = readTree(snapshotFile, maximumSnapshotBytes);
            if (!root.isObject() || !root.has("value")) {
                throw new IllegalArgumentException("persisted snapshot envelope must contain value");
            }
            var document = MarketReadJsonMapper.mapSnapshot(root.get("value"));
            snapshotCache = new CachedDocument(stamp, document);
            return document;
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Unable to load the persisted market snapshot", error);
        }
    }

    @Override
    public Document loadHistoryCoverage() {
        try {
            var files = historyFiles();
            var directoryStamp = directoryStamp(files);
            var cached = coverageCache;
            if (cached != null && cached.stamp().equals(directoryStamp)) return cached.document();

            var root = objectMapper.createObjectNode();
            for (var file : files) {
                var points = points(file);
                var name = stripJson(file.getFileName().toString()).toUpperCase(Locale.ROOT);
                var value = root.putObject(name);
                value.put("count", points.values().size());
                value.put("oldest", points.values().isEmpty() ? "" : points.values().getFirst().date());
                value.put("newest", points.values().isEmpty() ? "" : points.values().getLast().date());
                value.put("guaranteedYears", file.getFileName().toString().startsWith("fred-") ? 10 : 5);
            }
            var document = MarketReadJsonMapper.mapCoverage(root);
            coverageCache = new CachedCoverage(directoryStamp, document);
            return document;
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Unable to load history coverage", error);
        }
    }

    @Override
    public Document loadHistory(String source, String key) {
        try {
            var file = historyPath(source, key);
            var loaded = pointsOrEmpty(file);
            var request = new HistoryRequest(source, key);
            var cached = historyDocumentCache.get(request);
            if (cached != null && cached.stamp().equals(loaded.stamp())) return cached.document();

            var root = objectMapper.createObjectNode();
            root.put("source", source);
            root.put("key", key);
            root.put("count", loaded.values().size());
            root.set("points", pointArray(loaded.values()));
            var document = MarketReadJsonMapper.mapHistory(root, source, key);
            historyDocumentCache.put(request, new CachedDocument(loaded.stamp(), document));
            evictOldest(historyDocumentCache, MAX_DOCUMENT_CACHE_ENTRIES);
            return document;
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Unable to load history", error);
        }
    }

    @Override
    public Document loadHistorySeries(List<String> keys, String range, String interval) {
        try {
            var request = new SeriesRequest(keys, range, interval, utcDay());
            var selected = new LinkedHashMap<String, CachedPoints>();
            for (var compoundKey : keys) {
                var parts = compoundKey.split(":", -1);
                if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) continue;
                selected.put(compoundKey, pointsOrEmpty(historyPath(parts[0].toLowerCase(Locale.ROOT), parts[1])));
            }
            var fingerprint = selectedFingerprint(selected);
            var cached = seriesDocumentCache.get(request);
            if (cached != null && cached.fingerprint() == fingerprint) return cached.document();

            var root = objectMapper.createObjectNode();
            var keyArray = root.putArray("keys");
            keys.forEach(keyArray::add);
            root.put("range", range);
            root.put("interval", interval);
            var series = root.putObject("series");
            selected.forEach((compoundKey, loaded) -> series.set(
                    compoundKey,
                    pointArray(downsample(filterRange(loaded.values(), range), interval))
            ));
            var document = MarketReadJsonMapper.mapSeries(root, keys, range, interval);
            seriesDocumentCache.put(request, new CachedSeriesDocument(fingerprint, document, System.nanoTime()));
            evictOldestSeries();
            return document;
        } catch (MarketReadUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw unavailable("Unable to load history series", error);
        }
    }

    private List<HistoryPoint> filterRange(List<HistoryPoint> points, String range) {
        var days = RANGE_DAYS.get(range);
        if (days == null) return List.of(); // Preserve the JavaScript NaN cutoff behavior.
        var cutoff = clock.millis() - days * 86_400_000L;
        return points.stream().filter(point -> dateMillis(point.date()) >= cutoff).toList();
    }

    private static List<HistoryPoint> downsample(List<HistoryPoint> points, String interval) {
        if (points.isEmpty()) return List.of();
        var step = INTERVAL_STEPS.get(interval);
        var result = new ArrayList<HistoryPoint>();
        for (var index = 0; index < points.size(); index++) {
            if ((step != null && index % step == 0) || index == points.size() - 1) result.add(points.get(index));
        }
        return List.copyOf(result);
    }

    private CachedPoints pointsOrEmpty(Path file) throws IOException {
        if (!Files.exists(file)) return CachedPoints.EMPTY;
        return points(file);
    }

    private CachedPoints points(Path file) throws IOException {
        var currentStamp = stamp(file, maximumHistoryFileBytes);
        var current = pointCache.get(file);
        if (current != null && current.stamp().equals(currentStamp)) return current;

        var root = readTree(file, maximumHistoryFileBytes);
        if (!root.isArray()) throw new IllegalArgumentException("history file must contain an array");
        var values = new ArrayList<HistoryPoint>(root.size());
        for (var item : root) {
            if (!item.isObject()) throw new IllegalArgumentException("history point must be an object");
            var date = item.get("date");
            if (date == null) date = item.get("observationDate");
            var value = item.get("value");
            if (date == null || !date.isString() || date.stringValue().isBlank()) {
                throw new IllegalArgumentException("history point date must be non-blank text");
            }
            if (value == null || !value.isNumber()) {
                throw new IllegalArgumentException("history point value must be numeric");
            }
            values.add(new HistoryPoint(date.stringValue(), value.deepCopy()));
        }
        var loaded = new CachedPoints(currentStamp, List.copyOf(values));
        pointCache.put(file, loaded);
        evictOldest(pointCache, maximumHistoryFiles);
        return loaded;
    }

    private ArrayNode pointArray(List<HistoryPoint> points) {
        var result = objectMapper.createArrayNode();
        points.forEach(point -> {
            var value = result.addObject();
            value.put("date", point.date());
            value.set("value", point.value());
        });
        return result;
    }

    private List<Path> historyFiles() throws IOException {
        if (!Files.isDirectory(historyDirectory)) {
            throw new MarketReadUnavailableException("History directory is unavailable");
        }
        try (var stream = Files.list(historyDirectory)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit((long) maximumHistoryFiles + 1)
                    .toList();
            if (files.size() > maximumHistoryFiles) {
                throw new IllegalArgumentException("history directory exceeds the configured file bound");
            }
            return files;
        }
    }

    private Path historyPath(String source, String key) {
        var fileName = (source.toLowerCase(Locale.ROOT) + "-" + key.toLowerCase(Locale.ROOT) + ".json");
        var resolved = historyDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(historyDirectory)) throw new IllegalArgumentException("history path escapes its directory");
        return resolved;
    }

    private JsonNode readTree(Path file, long maximumBytes) throws IOException {
        var bytes = Files.readAllBytes(file);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("market data file exceeds its configured bound");
        var root = objectMapper.readTree(bytes);
        if (root == null) throw new IllegalArgumentException("market data file is empty");
        return root;
    }

    private static FileStamp stamp(Path file, long maximumBytes) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("market data file does not exist: " + file.getFileName());
        var attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (attributes.size() > maximumBytes) throw new IllegalArgumentException("market data file exceeds its configured bound");
        return new FileStamp(attributes.lastModifiedTime().toMillis(), attributes.size());
    }

    private static DirectoryStamp directoryStamp(List<Path> files) throws IOException {
        long fingerprint = 1;
        for (var file : files) {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class);
            fingerprint = 31 * fingerprint + file.getFileName().toString().hashCode();
            fingerprint = 31 * fingerprint + Long.hashCode(attributes.lastModifiedTime().toMillis());
            fingerprint = 31 * fingerprint + Long.hashCode(attributes.size());
        }
        return new DirectoryStamp(files.size(), fingerprint);
    }

    private static long selectedFingerprint(Map<String, CachedPoints> selected) {
        long fingerprint = 1;
        for (var entry : selected.entrySet()) {
            fingerprint = 31 * fingerprint + entry.getKey().hashCode();
            fingerprint = 31 * fingerprint + entry.getValue().stamp().hashCode();
        }
        return fingerprint;
    }

    private LocalDate utcDay() {
        return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long dateMillis(String date) {
        return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static String stripJson(String name) {
        return name.substring(0, name.length() - ".json".length());
    }

    private static Path absolute(Path value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value.normalize();
    }

    private static long positive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static int positive(int value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static <K, V> void evictOldest(ConcurrentHashMap<K, V> cache, int maximum) {
        while (cache.size() > maximum) {
            var key = cache.keys().nextElement();
            cache.remove(key);
        }
    }

    private void evictOldestSeries() {
        while (seriesDocumentCache.size() > MAX_SERIES_CACHE_ENTRIES) {
            var oldest = seriesDocumentCache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().loadedSequence()))
                    .orElse(null);
            if (oldest == null) return;
            seriesDocumentCache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static MarketReadUnavailableException unavailable(String message, Exception cause) {
        return new MarketReadUnavailableException(message, cause);
    }

    private record HistoryPoint(String date, JsonNode value) {
    }

    private record FileStamp(long modifiedAtMillis, long size) {
        private static final FileStamp MISSING = new FileStamp(-1, 0);
    }

    private record DirectoryStamp(int fileCount, long fingerprint) {
    }

    private record CachedPoints(FileStamp stamp, List<HistoryPoint> values) {
        private static final CachedPoints EMPTY = new CachedPoints(FileStamp.MISSING, List.of());
    }

    private record CachedDocument(FileStamp stamp, Document document) {
    }

    private record CachedCoverage(DirectoryStamp stamp, Document document) {
    }

    private record CachedSeriesDocument(long fingerprint, Document document, long loadedSequence) {
    }

    private record HistoryRequest(String source, String key) {
    }

    private record SeriesRequest(List<String> keys, String range, String interval, LocalDate utcDay) {
        private SeriesRequest {
            keys = List.copyOf(keys);
        }
    }
}
