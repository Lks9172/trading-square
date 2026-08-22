package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "macrosquare.yahoo-company-price-history")
public record YahooCompanyPriceHistoryProperties(
        List<URI> baseUrls,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        int lookbackDays,
        Duration cacheTtl,
        Duration staleTtl,
        int maxConcurrentFetches
) {
    public YahooCompanyPriceHistoryProperties {
        baseUrls = List.copyOf(baseUrls == null ? List.of() : baseUrls);
        if (baseUrls.isEmpty() || baseUrls.stream().anyMatch(baseUrl -> baseUrl == null || !baseUrl.isAbsolute())) {
            throw new IllegalArgumentException("Yahoo price-history baseUrls must contain absolute URIs");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("Yahoo price-history userAgent must not be blank");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        if (lookbackDays < 120) throw new IllegalArgumentException("lookbackDays must be at least 120");
        if (cacheTtl == null || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must not be negative");
        }
        if (staleTtl == null || staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
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
