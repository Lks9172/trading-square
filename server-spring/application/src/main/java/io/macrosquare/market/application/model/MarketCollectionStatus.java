package io.macrosquare.market.application.model;

import io.macrosquare.market.domain.observation.MarketDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Last completed collection attempt for one market source.
 *
 * <p>This is operational evidence, not an investment signal. A degraded run can
 * still persist usable observations while explicitly retaining the failed keys.</p>
 */
public record MarketCollectionStatus(
        MarketDataSource source,
        State state,
        Instant attemptedAt,
        Instant completedAt,
        int collected,
        int persisted,
        List<String> failureKeys,
        String failureType
) {
    private static final int MAXIMUM_FAILURE_KEYS = 32;

    public MarketCollectionStatus {
        source = Objects.requireNonNull(source);
        state = Objects.requireNonNull(state);
        attemptedAt = Objects.requireNonNull(attemptedAt);
        completedAt = Objects.requireNonNull(completedAt);
        if (completedAt.isBefore(attemptedAt)) {
            throw new IllegalArgumentException("completedAt must not precede attemptedAt");
        }
        if (collected < 0 || persisted < 0 || persisted > collected) {
            throw new IllegalArgumentException("collection status counts are invalid");
        }
        failureKeys = List.copyOf(failureKeys == null ? List.of() : failureKeys.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(MAXIMUM_FAILURE_KEYS)
                .toList());
        failureType = failureType == null ? "" : failureType.trim();
        if (state == State.SUCCESS && (!failureKeys.isEmpty() || !failureType.isBlank())) {
            throw new IllegalArgumentException("successful collection cannot contain failures");
        }
    }

    public static MarketCollectionStatus from(MarketCollectionReport report) {
        Objects.requireNonNull(report);
        var failures = report.failures().stream().map(MarketCollectionBatch.Failure::key).toList();
        var state = report.successful()
                ? State.SUCCESS
                : report.persisted() > 0 ? State.DEGRADED : State.FAILED;
        var failureType = !report.failures().isEmpty()
                ? report.providerPolicyLimitedOnly() ? "PROVIDER_POLICY_UNAVAILABLE" : "SOURCE_GAP"
                : report.persisted() != report.collected()
                ? "PERSISTENCE_COUNT_MISMATCH"
                : "";
        return new MarketCollectionStatus(
                report.source(), state, report.startedAt(), report.completedAt(),
                report.collected(), report.persisted(), failures,
                failureType);
    }

    public static MarketCollectionStatus failed(
            MarketDataSource source,
            Instant attemptedAt,
            Instant completedAt,
            Throwable failure
    ) {
        return new MarketCollectionStatus(
                source, State.FAILED, attemptedAt, completedAt, 0, 0, List.of(),
                failure == null ? "Unknown" : failure.getClass().getSimpleName());
    }

    public enum State {
        SUCCESS,
        DEGRADED,
        FAILED
    }
}
