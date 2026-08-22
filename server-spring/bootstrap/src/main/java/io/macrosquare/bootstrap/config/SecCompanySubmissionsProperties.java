package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.sec-company-submissions")
public record SecCompanySubmissionsProperties(
        URI baseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl,
        Duration staleTtl,
        int recentFilingLimit,
        int parityFilingLimit,
        int maxConcurrentFetches
) {
    public SecCompanySubmissionsProperties {
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
        if (recentFilingLimit < 1 || recentFilingLimit > 1000) {
            throw new IllegalArgumentException("recentFilingLimit must be between 1 and 1000");
        }
        if (parityFilingLimit < 1 || parityFilingLimit > recentFilingLimit) {
            throw new IllegalArgumentException("parityFilingLimit must be between 1 and recentFilingLimit");
        }
        if (maxConcurrentFetches < 1) {
            throw new IllegalArgumentException("maxConcurrentFetches must be positive");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
