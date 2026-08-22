package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.narrative-sources")
public record NarrativeSourceProperties(
        boolean enabled,
        URI googleNewsBaseUrl,
        URI wikimediaBaseUrl,
        URI youtubeBaseUrl,
        String youtubeApiKey,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration fixedDelay,
        Duration interRequestDelay,
        long maximumResponseBytes
) {
    public NarrativeSourceProperties {
        official(googleNewsBaseUrl, "news.google.com", "googleNewsBaseUrl");
        official(wikimediaBaseUrl, "wikimedia.org", "wikimediaBaseUrl");
        official(youtubeBaseUrl, "www.googleapis.com", "youtubeBaseUrl");
        youtubeApiKey = youtubeApiKey == null ? "" : youtubeApiKey.trim();
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent is required");
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(startupDelay, "startupDelay");
        positive(fixedDelay, "fixedDelay");
        nonNegative(interRequestDelay, "interRequestDelay");
        if (maximumResponseBytes < 1_024 || maximumResponseBytes > 10_000_000) {
            throw new IllegalArgumentException("maximumResponseBytes is out of range");
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
