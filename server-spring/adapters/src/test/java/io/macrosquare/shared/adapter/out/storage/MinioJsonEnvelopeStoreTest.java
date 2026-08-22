package io.macrosquare.shared.adapter.out.storage;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinioJsonEnvelopeStoreTest {

    private final InMemoryObjectStorage storage = new InMemoryObjectStorage();
    private final MinioJsonEnvelopeStore store = new MinioJsonEnvelopeStore(
            storage, new ObjectMapper(), 1024 * 1024, 16, Duration.ofMinutes(5));

    @Test
    void runtimeProjectionOverridesImmutableSeedWithTheSameLogicalName() {
        storage.rawPut("seed-projections/sample.json", envelope("seed"));
        storage.rawPut("projections/sample.json", envelope("runtime"));

        assertEquals("runtime", store.findValue("sample.json").orElseThrow().get("source").stringValue());
    }

    @Test
    void listingDeduplicatesRuntimeAndSeedNamesAndRetainsFallbacks() {
        storage.rawPut("seed-projections/company-a.json", envelope("seed-a"));
        storage.rawPut("seed-projections/company-b.json", envelope("seed-b"));
        storage.rawPut("projections/company-a.json", envelope("runtime-a"));

        var values = store.listValues("company-", 10);

        assertEquals(List.of("company-a.json", "company-b.json"),
                values.stream().map(JsonEnvelopeStore.NamedValue::name).sorted().toList());
        assertEquals("runtime-a", values.stream()
                .filter(value -> value.name().equals("company-a.json"))
                .findFirst().orElseThrow().value().get("source").stringValue());
    }

    @Test
    void rejectsMalformedEnvelopeRatherThanReturningAmbiguousEmptyData() {
        storage.rawPut("seed-projections/broken.json", "{\"notValue\":true}".getBytes(StandardCharsets.UTF_8));

        assertThrows(ObjectStorageException.class, () -> store.findValue("broken.json"));
    }

    @Test
    void savesOnlyToTheRuntimeProjectionNamespace() {
        store.saveEnvelope("saved.json", envelope("saved"), "application/json");

        assertEquals("saved", store.findValue("saved.json").orElseThrow().get("source").stringValue());
        assertEquals(List.of("projections/saved.json"), storage.list("projections/", 10));
    }

    private static byte[] envelope(String source) {
        return ("{\"key\":\"test\",\"updatedAt\":\"2026-07-21T00:00:00Z\","
                + "\"value\":{\"source\":\"" + source + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static final class InMemoryObjectStorage implements ObjectStorage {
        private final Map<String, StoredObject> values = new LinkedHashMap<>();

        void rawPut(String key, byte[] content) {
            put(key, content, "application/json", Map.of());
        }

        @Override
        public Optional<StoredObject> find(String objectKey, long maximumBytes) {
            var value = values.get(objectKey);
            if (value == null) return Optional.empty();
            if (value.content().length > maximumBytes) throw new ObjectStorageException("bounded read exceeded");
            return Optional.of(value);
        }

        @Override
        public StoredObject put(String objectKey, byte[] content, String contentType, Map<String, String> metadata) {
            var value = new StoredObject(
                    objectKey, content, contentType, "etag-" + values.size(), "v" + values.size(), Instant.EPOCH);
            values.put(objectKey, value);
            return value;
        }

        @Override
        public List<String> list(String prefix, int limit) {
            return values.keySet().stream().filter(key -> key.startsWith(prefix)).sorted().limit(limit).toList();
        }
    }
}
