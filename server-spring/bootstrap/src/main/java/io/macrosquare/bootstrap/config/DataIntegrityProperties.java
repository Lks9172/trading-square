package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.integrity-monitor")
public record DataIntegrityProperties(
        boolean enabled,
        int expectedCompanyUniverse,
        int calculationVersion,
        Duration maximumSummaryAge,
        Duration initialDelay,
        Duration fixedDelay
) {
    public DataIntegrityProperties {
        if (expectedCompanyUniverse < 1) {
            throw new IllegalArgumentException("expectedCompanyUniverse must be positive");
        }
        if (calculationVersion < 1) {
            throw new IllegalArgumentException("calculationVersion must be positive");
        }
        if (maximumSummaryAge == null || maximumSummaryAge.isZero() || maximumSummaryAge.isNegative()
                || initialDelay == null || initialDelay.isNegative()
                || fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("integrity monitor durations are invalid");
        }
    }
}
