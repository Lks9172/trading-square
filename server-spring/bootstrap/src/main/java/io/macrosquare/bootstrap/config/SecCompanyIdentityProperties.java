package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.sec-company-identity")
public record SecCompanyIdentityProperties(
        URI baseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl,
        Duration staleTtl
) {
    public SecCompanyIdentityProperties {
        if (baseUrl == null || !baseUrl.isAbsolute()) throw new IllegalArgumentException("SEC baseUrl must be absolute");
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("SEC userAgent must not be blank");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        if (cacheTtl == null || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must not be negative");
        }
        if (staleTtl == null || staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
