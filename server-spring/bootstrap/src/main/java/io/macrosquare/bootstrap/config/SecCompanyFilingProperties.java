package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.sec-company-filings")
public record SecCompanyFilingProperties(
        URI baseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration cacheTtl,
        Duration staleTtl,
        Duration interRequestDelay,
        int maxIndexBytes,
        int maxDocumentBytes,
        int maxInlineXbrlBytes,
        int maxTextCharacters,
        int maxPdfPages,
        int maxDetailEntries,
        int maxTextEntries,
        int maxConcurrentFetches,
        int primaryFilingLimit,
        int attachmentFilingLimit,
        int materialLimit
) {
    public SecCompanyFilingProperties {
        if (baseUrl == null || !baseUrl.isAbsolute() || !"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException("SEC filing baseUrl must be absolute HTTPS");
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("SEC userAgent must not be blank");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        requireNonNegative(cacheTtl, "cacheTtl");
        if (staleTtl == null || staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
        requireNonNegative(interRequestDelay, "interRequestDelay");
        if (maxIndexBytes < 1 || maxDocumentBytes < 1 || maxInlineXbrlBytes < 1
                || maxTextCharacters < 1 || maxPdfPages < 1
                || maxDetailEntries < 1 || maxTextEntries < 1 || maxConcurrentFetches < 1
                || primaryFilingLimit < 1 || attachmentFilingLimit < 1 || materialLimit < 1) {
            throw new IllegalArgumentException("SEC filing bounds must be positive");
        }
        if (maxDocumentBytes > 32 * 1024 * 1024 || maxInlineXbrlBytes > 32 * 1024 * 1024 || maxPdfPages > 200
                || attachmentFilingLimit > 10 || materialLimit > 100) {
            throw new IllegalArgumentException("SEC filing request/output limits are too large");
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
