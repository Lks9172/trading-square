package io.macrosquare.market.adapter.in.web;

import io.macrosquare.market.application.model.MarketReadModels.Document;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded serialized-payload cache for large immutable market read documents.
 *
 * <p>The payload is reused only while the exact same application projection
 * instance is returned. Outbound freshness therefore remains authoritative.</p>
 */
final class MarketReadPayloadCache {

    private static final int MAX_ENTRIES = 256;

    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Encoded> entries = new ConcurrentHashMap<>();

    MarketReadPayloadCache(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    byte[] payload(String key, Document source) {
        var encoded = entries.compute(Objects.requireNonNull(key), (ignored, current) -> {
            if (current != null && current.source() == source) return current;
            return new Encoded(
                    source,
                    objectMapper.writeValueAsBytes(MarketReadApiResponse.from(source)),
                    sequence.incrementAndGet()
            );
        });
        evictOldestIfRequired();
        return encoded.payload();
    }

    private void evictOldestIfRequired() {
        while (entries.size() > MAX_ENTRIES) {
            var oldest = entries.entrySet().stream()
                    .min((left, right) -> Long.compare(left.getValue().sequence(), right.getValue().sequence()))
                    .orElse(null);
            if (oldest == null) return;
            entries.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private record Encoded(Document source, byte[] payload, long sequence) {
    }
}
