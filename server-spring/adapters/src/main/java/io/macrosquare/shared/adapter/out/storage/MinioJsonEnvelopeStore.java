package io.macrosquare.shared.adapter.out.storage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Projection reader backed by versioned MinIO objects, with immutable seed fallback. */
public final class MinioJsonEnvelopeStore implements WritableJsonEnvelopeStore {

    private static final String RUNTIME_PREFIX = "projections/";
    private static final String SEED_PREFIX = "seed-projections/";

    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final long maximumDocumentBytes;
    private final int maximumCachedDocuments;
    private final long cacheTtlNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();

    public MinioJsonEnvelopeStore(
            ObjectStorage storage,
            ObjectMapper objectMapper,
            long maximumDocumentBytes,
            int maximumCachedDocuments,
            Duration cacheTtl
    ) {
        this(storage, objectMapper, maximumDocumentBytes, maximumCachedDocuments, cacheTtl, System::nanoTime);
    }

    MinioJsonEnvelopeStore(
            ObjectStorage storage,
            ObjectMapper objectMapper,
            long maximumDocumentBytes,
            int maximumCachedDocuments,
            Duration cacheTtl,
            LongSupplier nanoClock
    ) {
        this.storage = Objects.requireNonNull(storage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        if (maximumDocumentBytes <= 0) throw new IllegalArgumentException("maximumDocumentBytes must be positive");
        if (maximumCachedDocuments <= 0) throw new IllegalArgumentException("maximumCachedDocuments must be positive");
        Objects.requireNonNull(cacheTtl, "cacheTtl");
        if (cacheTtl.isZero() || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("cacheTtl must be between zero and one day");
        }
        this.maximumDocumentBytes = maximumDocumentBytes;
        this.maximumCachedDocuments = maximumCachedDocuments;
        this.cacheTtlNanos = cacheTtl.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock);
    }

    @Override
    public Optional<JsonNode> findValue(String fileName) {
        validateName(fileName, ".json");
        var now = nanoClock.getAsLong();
        var current = cache.get(fileName);
        if (current != null && now < current.expiresAtNanos()) return current.value();
        var object = findObject(fileName);
        if (object.isEmpty()) {
            remember(fileName, Optional.empty(), now);
            return Optional.empty();
        }
        try {
            var envelope = objectMapper.readTree(object.get().content());
            if (envelope == null || !envelope.isObject() || !envelope.has("value") || envelope.get("value").isNull()) {
                throw new IllegalArgumentException("projection envelope must contain a non-null value");
            }
            var value = Optional.of(envelope.get("value"));
            remember(fileName, value, now);
            return value;
        } catch (RuntimeException error) {
            throw new ObjectStorageException("Unable to parse object projection " + fileName, error);
        }
    }

    @Override
    public List<NamedValue> listValues(String prefix, int limit) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9._=-]+") || limit <= 0) return List.of();
        var names = new LinkedHashMap<String, Boolean>();
        storage.list(RUNTIME_PREFIX + prefix, limit).forEach(key -> names.put(baseName(key, RUNTIME_PREFIX), true));
        if (names.size() < limit) {
            storage.list(SEED_PREFIX + prefix, limit - names.size())
                    .forEach(key -> names.putIfAbsent(baseName(key, SEED_PREFIX), true));
        }
        return names.keySet().stream()
                .filter(name -> name.endsWith(".json"))
                .limit(limit)
                .map(name -> findValue(name).map(value -> new NamedValue(name, value)).orElse(null))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<String> findText(String fileName, long maximumBytes) {
        validateName(fileName, ".txt");
        var bound = Math.min(maximumDocumentBytes, maximumBytes);
        return storage.find(RUNTIME_PREFIX + fileName, bound)
                .or(() -> storage.find(SEED_PREFIX + fileName, bound))
                .map(value -> new String(value.content(), StandardCharsets.UTF_8));
    }

    @Override
    public void saveEnvelope(
            String fileName,
            byte[] content,
            String contentType
    ) {
        validateName(fileName, ".json");
        if (content.length > maximumDocumentBytes) throw new IllegalArgumentException("projection exceeds its bound");
        storage.put(RUNTIME_PREFIX + fileName, content, contentType, java.util.Map.of("projection", fileName));
        cache.remove(fileName);
    }

    private Optional<ObjectStorage.StoredObject> findObject(String fileName) {
        return storage.find(RUNTIME_PREFIX + fileName, maximumDocumentBytes)
                .or(() -> storage.find(SEED_PREFIX + fileName, maximumDocumentBytes));
    }

    private static void validateName(String fileName, String suffix) {
        if (fileName == null || !fileName.matches("[A-Za-z0-9._=-]+" + java.util.regex.Pattern.quote(suffix))) {
            throw new IllegalArgumentException("invalid projection file name");
        }
    }

    private static String baseName(String key, String prefix) {
        if (!key.startsWith(prefix)) throw new IllegalArgumentException("object prefix mismatch");
        return key.substring(prefix.length());
    }

    private void remember(String fileName, Optional<JsonNode> value, long now) {
        cache.put(fileName, new CachedValue(value, now + cacheTtlNanos, now));
        while (cache.size() > maximumCachedDocuments) {
            var oldest = cache.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().loadedAtNanos()))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private record CachedValue(Optional<JsonNode> value, long expiresAtNanos, long loadedAtNanos) {
        private CachedValue {
            value = Objects.requireNonNull(value);
        }
    }
}
