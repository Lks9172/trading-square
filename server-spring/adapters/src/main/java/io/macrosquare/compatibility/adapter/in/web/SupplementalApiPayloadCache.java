package io.macrosquare.compatibility.adapter.in.web;

import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class SupplementalApiPayloadCache {

    private static final int MAX_ENTRIES = 256;
    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Encoded> entries = new ConcurrentHashMap<>();

    SupplementalApiPayloadCache(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    byte[] payload(String key, Document source) {
        var encoded = entries.compute(key, (ignored, current) -> {
            if (current != null && current.source == source) return current;
            return new Encoded(
                    source,
                    objectMapper.writeValueAsBytes(SupplementalApiWebMapper.response(source)),
                    sequence.incrementAndGet()
            );
        });
        evict();
        return encoded.payload;
    }

    private void evict() {
        while (entries.size() > MAX_ENTRIES) {
            var oldest = entries.entrySet().stream()
                    .min((left, right) -> Long.compare(left.getValue().sequence, right.getValue().sequence))
                    .orElse(null);
            if (oldest == null) return;
            entries.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private record Encoded(Document source, byte[] payload, long sequence) {
    }
}
