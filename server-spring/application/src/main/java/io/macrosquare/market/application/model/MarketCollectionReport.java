package io.macrosquare.market.application.model;

import io.macrosquare.market.domain.observation.MarketDataSource;

import java.time.Instant;
import java.util.List;

public record MarketCollectionReport(
        MarketDataSource source,
        Instant startedAt,
        Instant completedAt,
        int collected,
        int persisted,
        List<MarketCollectionBatch.Failure> failures
) {
    public MarketCollectionReport {
        failures = List.copyOf(failures == null ? List.of() : failures);
        if (collected < 0 || persisted < 0 || persisted > collected) {
            throw new IllegalArgumentException("collection counts are invalid");
        }
    }

    public boolean successful() {
        // A provider response is not a successful collection until every
        // accepted observation reached the owned store. Treating collected>0
        // as success allowed a zero/partial persistence result to publish a
        // green collector state and postpone recurrence detection.
        return collected > 0 && persisted == collected && failures.isEmpty();
    }

    public boolean providerPolicyLimitedOnly() {
        return collected > 0
                && persisted == collected
                && !failures.isEmpty()
                && failures.stream().allMatch(failure ->
                        failure.kind() == MarketCollectionBatch.FailureKind.PROVIDER_POLICY_UNAVAILABLE);
    }
}
