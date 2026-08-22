package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.company-analyst")
public record CompanyAnalystProperties(
        Path sourceCacheDirectory,
        URI cookieUrl,
        URI crumbUrl,
        URI quoteSummaryBaseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration authCacheTtl,
        Duration consensusCacheTtl,
        Duration consensusStaleTtl,
        Duration interTickerDelay,
        int minimumSuccessfulTickers
) {
    public CompanyAnalystProperties {
        if (sourceCacheDirectory == null || !sourceCacheDirectory.isAbsolute()) {
            throw new IllegalArgumentException("sourceCacheDirectory must be an absolute path");
        }
        requireAbsolute(cookieUrl, "cookieUrl");
        requireAbsolute(crumbUrl, "crumbUrl");
        requireAbsolute(quoteSummaryBaseUrl, "quoteSummaryBaseUrl");
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requireNonNegative(authCacheTtl, "authCacheTtl");
        requireNonNegative(consensusCacheTtl, "consensusCacheTtl");
        requireNonNegative(consensusStaleTtl, "consensusStaleTtl");
        if (consensusStaleTtl.compareTo(consensusCacheTtl) < 0) {
            throw new IllegalArgumentException("consensusStaleTtl must be >= consensusCacheTtl");
        }
        requireNonNegative(interTickerDelay, "interTickerDelay");
        if (minimumSuccessfulTickers < 1 || minimumSuccessfulTickers > 7) {
            throw new IllegalArgumentException("minimumSuccessfulTickers must be between 1 and 7");
        }
    }

    private static void requireAbsolute(URI value, String field) {
        if (value == null || !value.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be an absolute URI");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String field) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
