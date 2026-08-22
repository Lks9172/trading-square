package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.peer-discovery")
public record PeerDiscoveryProperties(
        boolean enabled,
        URI secDataBaseUrl,
        URI secArchiveBaseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration fixedDelay,
        Duration interRequestDelay,
        Duration universeCacheTtl,
        Duration taxonomyRefreshTtl,
        Duration missingGrace,
        long maximumSubmissionsBytes,
        int batchSize
) {
    public PeerDiscoveryProperties {
        official(secDataBaseUrl, "data.sec.gov", "secDataBaseUrl");
        official(secArchiveBaseUrl, "www.sec.gov", "secArchiveBaseUrl");
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent is required");
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(startupDelay, "startupDelay");
        positive(fixedDelay, "fixedDelay");
        nonNegative(interRequestDelay, "interRequestDelay");
        positive(universeCacheTtl, "universeCacheTtl");
        positive(taxonomyRefreshTtl, "taxonomyRefreshTtl");
        positive(missingGrace, "missingGrace");
        if (maximumSubmissionsBytes <= 0 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("peer discovery boundaries are invalid");
        }
    }

    private static void official(URI value, String host, String field) {
        if (value == null || !value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || !host.equalsIgnoreCase(value.getHost())) {
            throw new IllegalArgumentException(field + " must use official host " + host);
        }
    }

    private static void positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void nonNegative(Duration value, String field) {
        if (value == null || value.isNegative()) throw new IllegalArgumentException(field + " must be non-negative");
    }
}
