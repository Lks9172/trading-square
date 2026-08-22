package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.policy-collection")
public record PolicyCollectionProperties(
        boolean enabled,
        URI feedUrl,
        URI calendarUrl,
        URI treasuryListingUrl,
        URI ustrListingUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration fixedDelay,
        Duration interRequestDelay,
        long maximumFeedBytes,
        long maximumDocumentBytes,
        int maximumDocuments,
        int queryDocumentLimit,
        int historicalStatementLimit,
        int agencyDocumentLimit
) {
    public PolicyCollectionProperties {
        if (feedUrl == null || !feedUrl.isAbsolute()
                || !"https".equalsIgnoreCase(feedUrl.getScheme())
                || !"www.federalreserve.gov".equalsIgnoreCase(feedUrl.getHost())) {
            throw new IllegalArgumentException("feedUrl must use the official Federal Reserve HTTPS host");
        }
        official(calendarUrl, "www.federalreserve.gov", "calendarUrl");
        official(treasuryListingUrl, "home.treasury.gov", "treasuryListingUrl");
        official(ustrListingUrl, "ustr.gov", "ustrListingUrl");
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent is required");
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(startupDelay, "startupDelay");
        positive(fixedDelay, "fixedDelay");
        nonNegative(interRequestDelay, "interRequestDelay");
        if (maximumFeedBytes <= 0 || maximumDocumentBytes <= 0) {
            throw new IllegalArgumentException("Fed byte limits must be positive");
        }
        if (maximumDocuments < 1 || maximumDocuments > 120) {
            throw new IllegalArgumentException("maximumDocuments must be between 1 and 120");
        }
        if (queryDocumentLimit < 1 || queryDocumentLimit > 30) {
            throw new IllegalArgumentException("queryDocumentLimit must be between 1 and 30");
        }
        if (historicalStatementLimit < 0 || historicalStatementLimit > 120) {
            throw new IllegalArgumentException("historicalStatementLimit must be between 0 and 120");
        }
        if (agencyDocumentLimit < 1 || agencyDocumentLimit > 30) {
            throw new IllegalArgumentException("agencyDocumentLimit must be between 1 and 30");
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
        if (value == null || value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
    }
}
