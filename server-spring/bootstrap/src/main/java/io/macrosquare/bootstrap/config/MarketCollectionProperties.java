package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "macrosquare.market-collection")
public record MarketCollectionProperties(
        boolean enabled,
        boolean historySeedEnabled,
        boolean snapshotRefreshEnabled,
        Path directory,
        int maximumHistoryPoints,
        long maximumFileBytes,
        long maximumSeedFileBytes,
        URI fredBaseUrl,
        String fredApiKey,
        List<URI> yahooBaseUrls,
        URI cnnFearGreedUrl,
        URI alternativeFearGreedUrl,
        URI cboeDelayedQuotesBaseUrl,
        URI aaiiFeedUrl,
        URI naaimExposureUrl,
        URI stablecoinUrl,
        URI krxInvestorFlowUrl,
        String userAgent,
        Duration connectTimeout,
        Duration fredReadTimeout,
        Duration yahooReadTimeout,
        Duration supplementalReadTimeout,
        Duration startupDelay,
        Duration fredFixedDelay,
        Duration yahooFixedDelay,
        Duration fearGreedFixedDelay,
        Duration sentimentFixedDelay,
        Duration stablecoinFixedDelay,
        Duration krxFixedDelay,
        Duration snapshotStartupDelay,
        Duration snapshotFixedDelay,
        int fredConcurrency,
        int yahooConcurrency,
        int supplementalConcurrency
) {
    public MarketCollectionProperties {
        if (directory == null || !directory.isAbsolute()) throw new IllegalArgumentException("directory must be absolute");
        directory = directory.normalize();
        if (maximumHistoryPoints <= 0 || maximumHistoryPoints > 20_000) {
            throw new IllegalArgumentException("maximumHistoryPoints must be between 1 and 20000");
        }
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        if (maximumSeedFileBytes <= 0) throw new IllegalArgumentException("maximumSeedFileBytes must be positive");
        if (fredBaseUrl == null || !fredBaseUrl.isAbsolute()) throw new IllegalArgumentException("fredBaseUrl is invalid");
        yahooBaseUrls = List.copyOf(yahooBaseUrls == null ? List.of() : yahooBaseUrls);
        if (yahooBaseUrls.isEmpty() || yahooBaseUrls.stream().anyMatch(uri -> !uri.isAbsolute())) {
            throw new IllegalArgumentException("yahooBaseUrls must contain absolute URIs");
        }
        https(cnnFearGreedUrl, "cnnFearGreedUrl");
        https(alternativeFearGreedUrl, "alternativeFearGreedUrl");
        https(cboeDelayedQuotesBaseUrl, "cboeDelayedQuotesBaseUrl");
        https(aaiiFeedUrl, "aaiiFeedUrl");
        https(naaimExposureUrl, "naaimExposureUrl");
        https(stablecoinUrl, "stablecoinUrl");
        https(krxInvestorFlowUrl, "krxInvestorFlowUrl");
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent is required");
        positive(connectTimeout, "connectTimeout");
        positive(fredReadTimeout, "fredReadTimeout");
        positive(yahooReadTimeout, "yahooReadTimeout");
        positive(supplementalReadTimeout, "supplementalReadTimeout");
        nonNegative(startupDelay, "startupDelay");
        positive(fredFixedDelay, "fredFixedDelay");
        positive(yahooFixedDelay, "yahooFixedDelay");
        positive(fearGreedFixedDelay, "fearGreedFixedDelay");
        positive(sentimentFixedDelay, "sentimentFixedDelay");
        positive(stablecoinFixedDelay, "stablecoinFixedDelay");
        positive(krxFixedDelay, "krxFixedDelay");
        nonNegative(snapshotStartupDelay, "snapshotStartupDelay");
        positive(snapshotFixedDelay, "snapshotFixedDelay");
        if (fredConcurrency < 1 || fredConcurrency > 4) {
            throw new IllegalArgumentException("fredConcurrency must be between 1 and 4");
        }
        if (yahooConcurrency < 1 || yahooConcurrency > 16) {
            throw new IllegalArgumentException("yahooConcurrency must be between 1 and 16");
        }
        if (supplementalConcurrency < 1 || supplementalConcurrency > 8) {
            throw new IllegalArgumentException("supplementalConcurrency must be between 1 and 8");
        }
        fredApiKey = fredApiKey == null ? "" : fredApiKey.trim();
    }

    private static void https(URI uri, String field) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URI");
        }
    }

    private static void positive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void nonNegative(Duration duration, String field) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
