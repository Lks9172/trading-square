package io.macrosquare.market.application.model;

import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;

import java.time.Instant;
import java.util.List;

public record MarketCollectionBatch(
        MarketDataSource source,
        Instant startedAt,
        Instant completedAt,
        List<MarketObservation> observations,
        List<Failure> failures
) {
    public MarketCollectionBatch {
        if (source == null) throw new IllegalArgumentException("source is required");
        if (startedAt == null || completedAt == null || completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("collection timestamps are invalid");
        }
        observations = List.copyOf(observations == null ? List.of() : observations);
        failures = List.copyOf(failures == null ? List.of() : failures);
        if (observations.stream().anyMatch(item -> item.source() != source)) {
            throw new IllegalArgumentException("batch contains an observation from another source");
        }
    }

    public record Failure(String key, String reason, FailureKind kind) {
        public Failure(String key, String reason) {
            this(key, reason, FailureKind.SOURCE_GAP);
        }

        public Failure {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("failure key is required");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("failure reason is required");
            if (reason.length() > 500) reason = reason.substring(0, 500);
            if (kind == null) throw new IllegalArgumentException("failure kind is required");
        }
    }

    /** Operational classification only; it must never be converted into a market value. */
    public enum FailureKind {
        SOURCE_GAP,
        PROVIDER_POLICY_UNAVAILABLE
    }
}
