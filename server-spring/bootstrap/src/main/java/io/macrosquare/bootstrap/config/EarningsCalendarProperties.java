package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.earnings-calendar")
public record EarningsCalendarProperties(
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl
) {
    public EarningsCalendarProperties {
        if (baseUrl == null || !baseUrl.isAbsolute()) throw new IllegalArgumentException("baseUrl must be absolute");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()
                || cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("earnings calendar durations must be positive");
        }
    }
}
