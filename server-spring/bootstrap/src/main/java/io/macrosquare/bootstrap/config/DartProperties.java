package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "macrosquare.dart")
public record DartProperties(
        boolean enabled,
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration fixedDelay,
        Duration interRequestDelay,
        Duration directoryRefreshTtl,
        long maximumCompressedBytes,
        long maximumUncompressedBytes,
        int lookbackDays,
        List<String> stockCodes
) {
    public DartProperties {
        if (baseUrl == null || !baseUrl.isAbsolute() || !"https".equalsIgnoreCase(baseUrl.getScheme())
                || !"opendart.fss.or.kr".equalsIgnoreCase(baseUrl.getHost())) {
            throw new IllegalArgumentException("baseUrl must use official OpenDART host");
        }
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (enabled && apiKey.isBlank()) throw new IllegalArgumentException("DART_API_KEY is required when enabled");
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(startupDelay, "startupDelay");
        positive(fixedDelay, "fixedDelay");
        nonNegative(interRequestDelay, "interRequestDelay");
        positive(directoryRefreshTtl, "directoryRefreshTtl");
        if (maximumCompressedBytes <= 0 || maximumUncompressedBytes <= 0
                || lookbackDays < 1 || lookbackDays > 365) {
            throw new IllegalArgumentException("OpenDART boundaries are invalid");
        }
        stockCodes = List.copyOf(stockCodes == null ? List.of() : stockCodes);
        if (stockCodes.stream().anyMatch(value -> value == null || !value.matches("\\d{6}"))) {
            throw new IllegalArgumentException("DART stock codes must contain six digits");
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
