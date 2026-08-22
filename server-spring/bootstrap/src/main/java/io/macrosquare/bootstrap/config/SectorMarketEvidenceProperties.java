package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.sector-market-evidence")
public record SectorMarketEvidenceProperties(
        boolean enabled,
        URI stateStreetBaseUrl,
        long maximumWorkbookBytes,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration fixedDelay
) {
    public SectorMarketEvidenceProperties {
        if (stateStreetBaseUrl == null || !stateStreetBaseUrl.isAbsolute()
                || !"https".equalsIgnoreCase(stateStreetBaseUrl.getScheme())) {
            throw new IllegalArgumentException("stateStreetBaseUrl must be an absolute HTTPS URI");
        }
        if (maximumWorkbookBytes < 1024 || maximumWorkbookBytes > 16L * 1024 * 1024) {
            throw new IllegalArgumentException("maximumWorkbookBytes is outside the safe range");
        }
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent is required");
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(startupDelay, "startupDelay");
        positive(fixedDelay, "fixedDelay");
    }

    private static void positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void nonNegative(Duration value, String field) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
