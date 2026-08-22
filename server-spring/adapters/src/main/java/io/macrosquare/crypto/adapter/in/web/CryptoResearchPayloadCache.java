package io.macrosquare.crypto.adapter.in.web;

import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reuses the serialized representation while the immutable application
 * projection instance is unchanged. Data freshness remains owned by the
 * outbound read cache; this class only avoids rebuilding a large web DTO graph
 * and JSON byte array for every request.
 */
final class CryptoResearchPayloadCache {

    private final ObjectMapper objectMapper;
    private final ReentrantLock catalogLock = new ReentrantLock(true);
    private volatile Encoded<Catalog> catalog;
    private final ConcurrentHashMap<String, Encoded<Research>> details = new ConcurrentHashMap<>();

    CryptoResearchPayloadCache(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    byte[] catalog(Catalog source) {
        var current = catalog;
        if (current != null && current.source() == source) return current.payload();
        catalogLock.lock();
        try {
            current = catalog;
            if (current != null && current.source() == source) return current.payload();
            var encoded = new Encoded<>(
                    source,
                    objectMapper.writeValueAsBytes(CryptoResearchApiResponse.Catalog.from(source))
            );
            catalog = encoded;
            return encoded.payload();
        } finally {
            catalogLock.unlock();
        }
    }

    byte[] detail(Research source) {
        return details.compute(source.profile().symbol(), (symbol, current) -> {
            if (current != null && current.source() == source) return current;
            return new Encoded<>(
                    source,
                    objectMapper.writeValueAsBytes(CryptoResearchApiResponse.Research.from(source))
            );
        }).payload();
    }

    private record Encoded<T>(T source, byte[] payload) {
    }
}
