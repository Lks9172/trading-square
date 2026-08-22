package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "macrosquare.yahoo-company-quote")
public record YahooCompanyQuoteProperties(
        List<URI> baseUrls,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl,
        Duration staleTtl,
        int maxConcurrentFetches
) {
    public YahooCompanyQuoteProperties {
        baseUrls = List.copyOf(baseUrls == null ? List.of() : baseUrls);
        if (baseUrls.isEmpty() || baseUrls.stream().anyMatch(baseUrl -> baseUrl == null || !baseUrl.isAbsolute())) {
            throw new IllegalArgumentException("Yahoo baseUrls must contain absolute URIs");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("Yahoo userAgent must not be blank");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
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
