package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.yahoo-request-throttle")
public record YahooRequestThrottleProperties(
        Duration minimumInterval,
        Duration rateLimitBackoff
) {
    public YahooRequestThrottleProperties {
        if (minimumInterval == null || minimumInterval.isZero() || minimumInterval.isNegative()) {
            throw new IllegalArgumentException("Yahoo minimumInterval must be positive");
        }
        if (rateLimitBackoff == null || rateLimitBackoff.isZero() || rateLimitBackoff.isNegative()) {
            throw new IllegalArgumentException("Yahoo rateLimitBackoff must be positive");
        }
    }
}
