package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyReadModels.Research;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded cache for the serialized representation of immutable company detail projections.
 *
 * <p>Freshness remains owned by the outbound read cache. An entry is reused only
 * while the exact same application projection instance is returned, so this
 * optimization cannot extend data freshness.</p>
 */
final class CompanyResearchPayloadCache {

    private static final int MAX_ENTRIES = 128;

    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Encoded> entries = new ConcurrentHashMap<>();

    CompanyResearchPayloadCache(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    byte[] detail(String ticker, Research source) {
        var key = normalize(ticker);
        var encoded = entries.compute(key, (ignored, current) -> {
            if (current != null && current.source() == source) return current;
            return new Encoded(
                    source,
                    objectMapper.writeValueAsBytes(CompanyReadApiResponse.Research.from(source)),
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

    private static String normalize(String ticker) {
        return Objects.requireNonNull(ticker, "ticker").trim().toUpperCase(Locale.ROOT);
    }

    private record Encoded(Research source, byte[] payload, long sequence) {
    }
}
