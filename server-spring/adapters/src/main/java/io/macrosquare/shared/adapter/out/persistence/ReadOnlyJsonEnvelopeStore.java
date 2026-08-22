package io.macrosquare.shared.adapter.out.persistence;

import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded, path-safe reader for the source-cache {@code {value: ...}} envelope. */
public final class ReadOnlyJsonEnvelopeStore implements JsonEnvelopeStore {

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final long maximumFileBytes;
    private final int maximumCachedFiles;
    private final ConcurrentHashMap<Path, CachedValue> cache = new ConcurrentHashMap<>();

    public ReadOnlyJsonEnvelopeStore(
            ObjectMapper objectMapper,
            Path directory,
            long maximumFileBytes,
            int maximumCachedFiles
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.directory = absolute(directory);
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        if (maximumCachedFiles <= 0) throw new IllegalArgumentException("maximumCachedFiles must be positive");
        this.maximumFileBytes = maximumFileBytes;
        this.maximumCachedFiles = maximumCachedFiles;
    }

    @Override
    public Optional<JsonNode> findValue(String fileName) {
        var path = resolve(fileName);
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            var stamp = stamp(path);
            var current = cache.get(path);
            if (current != null && current.stamp().equals(stamp)) return Optional.of(current.value());

            var bytes = Files.readAllBytes(path);
            if (bytes.length > maximumFileBytes) throw new IllegalArgumentException("projection file exceeds its bound");
            var envelope = objectMapper.readTree(bytes);
            if (envelope == null || !envelope.isObject() || !envelope.has("value") || envelope.get("value").isNull()) {
                throw new IllegalArgumentException("projection envelope must contain a non-null value");
            }
            var loaded = new CachedValue(stamp, envelope.get("value"), System.nanoTime());
            cache.put(path, loaded);
            evictOldest();
            return Optional.of(loaded.value());
        } catch (IOException | RuntimeException error) {
            throw new JsonEnvelopeReadException("Unable to read persisted projection " + fileName, error);
        }
    }

    @Override
    public java.util.List<NamedValue> listValues(String prefix, int limit) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9._=-]+") || limit <= 0) return java.util.List.of();
        if (!Files.isDirectory(directory)) return java.util.List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".json"))
                    .sorted()
                    .limit(limit)
                    .map(name -> findValue(name).map(value -> new NamedValue(name, value)).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException error) {
            throw new JsonEnvelopeReadException("Unable to list persisted projections", error);
        }
    }

    @Override
    public Optional<String> findText(String fileName, long maximumBytes) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._=-]+\\.txt") || maximumBytes <= 0) {
            throw new IllegalArgumentException("invalid projection text request");
        }
        var path = directory.resolve(fileName).normalize();
        if (!path.startsWith(directory) || !Files.isRegularFile(path)) return Optional.empty();
        try {
            var bytes = Files.readAllBytes(path);
            if (bytes.length > maximumBytes) throw new IllegalArgumentException("projection text exceeds its bound");
            return Optional.of(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new JsonEnvelopeReadException("Unable to read persisted projection text", error);
        }
    }

    private FileStamp stamp(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.size() > maximumFileBytes) throw new IllegalArgumentException("projection file exceeds its bound");
        return new FileStamp(attributes.lastModifiedTime().toMillis(), attributes.size());
    }

    private Path resolve(String fileName) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._=-]+\\.json")) {
            throw new IllegalArgumentException("invalid projection file name");
        }
        var path = directory.resolve(fileName).normalize();
        if (!path.startsWith(directory)) throw new IllegalArgumentException("projection path escapes its directory");
        return path;
    }

    private void evictOldest() {
        while (cache.size() > maximumCachedFiles) {
            var oldest = cache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().loadedSequence()))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static Path absolute(Path path) {
        Objects.requireNonNull(path, "directory");
        if (!path.isAbsolute()) throw new IllegalArgumentException("directory must be absolute");
        return path.normalize();
    }

    private record FileStamp(long modifiedAtMillis, long size) {
    }

    private record CachedValue(FileStamp stamp, JsonNode value, long loadedSequence) {
    }

    public static final class JsonEnvelopeReadException extends RuntimeException {
        public JsonEnvelopeReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
