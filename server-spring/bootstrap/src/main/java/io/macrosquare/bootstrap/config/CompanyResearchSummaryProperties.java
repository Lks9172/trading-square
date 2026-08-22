package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.company-research-summary")
public record CompanyResearchSummaryProperties(
        boolean enabled,
        Duration startupDelay,
        Duration fixedDelay,
        int concurrency
) {
    public CompanyResearchSummaryProperties {
        if (startupDelay == null || startupDelay.isNegative()) {
            throw new IllegalArgumentException("startupDelay must not be negative");
        }
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("fixedDelay must be positive");
        }
        if (concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("concurrency must be between 1 and 32");
        }
    }
}
