package io.macrosquare.bootstrap.health;

import io.macrosquare.bootstrap.config.MarketDataProperties;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Readiness evidence for the last-valid market projection without calling an upstream provider. */
public final class SnapshotFreshnessHealthIndicator implements HealthIndicator {

    private static final Duration MINIMUM_STALE_THRESHOLD = Duration.ofMinutes(20);

    private final MarketDataProperties properties;
    private final LoadMarketSnapshotProjectionPort snapshots;
    private final Clock clock;
    private final AtomicLong ageSeconds = new AtomicLong(-1);

    public SnapshotFreshnessHealthIndicator(
            MarketDataProperties properties,
            LoadMarketSnapshotProjectionPort snapshots,
            Clock clock,
            MeterRegistry registry
    ) {
        this.properties = Objects.requireNonNull(properties);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.clock = Objects.requireNonNull(clock);
        Gauge.builder("macrosquare.snapshot.age.seconds", ageSeconds, AtomicLong::get)
                .description("Age of the active last-valid market snapshot in seconds")
                .register(Objects.requireNonNull(registry));
    }

    @Override
    public Health health() {
        try {
            var root = snapshots.loadCurrentOrSeed().root();
            var timestamp = root.fields().get("timestamp");
            if (!(timestamp instanceof TextValue text) || text.value().isBlank()) {
                ageSeconds.set(-1);
                return Health.down().withDetail("reason", "snapshot timestamp is unavailable").build();
            }
            var updatedAt = Instant.parse(text.value());
            var age = Duration.between(updatedAt, clock.instant());
            if (age.isNegative()) age = Duration.ZERO;
            ageSeconds.set(age.toSeconds());
            var threshold = staleThreshold(properties.cacheTtl());
            // A stale, last-valid projection is intentionally still serviceable. Expose
            // freshness as a detail/metric rather than taking the API out of readiness.
            return Health.up()
                    .withDetail("source", "owned-projection")
                    .withDetail("ageSeconds", age.toSeconds())
                    .withDetail("fresh", age.compareTo(threshold) <= 0)
                    .withDetail("staleAfterSeconds", threshold.toSeconds())
                    .build();
        } catch (Exception error) {
            ageSeconds.set(-1);
            return Health.down(error).withDetail("source", "owned-projection").build();
        }
    }

    private static Duration staleThreshold(Duration cacheTtl) {
        var calculated = cacheTtl.multipliedBy(3);
        return calculated.compareTo(MINIMUM_STALE_THRESHOLD) < 0 ? MINIMUM_STALE_THRESHOLD : calculated;
    }
}
