package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "macrosquare.institutional-collection")
public record InstitutionalCollectionProperties(
        boolean enabled,
        URI dataBaseUrl,
        URI archiveBaseUrl,
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration startupFreshness,
        Duration fixedDelay,
        Duration interRequestDelay,
        Duration identityDirectoryCacheTtl,
        long maximumIndexBytes,
        long maximumInformationTableBytes,
        int filingLimit,
        List<Manager> managers
) {
    public InstitutionalCollectionProperties {
        https(dataBaseUrl, "dataBaseUrl");
        https(archiveBaseUrl, "archiveBaseUrl");
        if (userAgent == null || userAgent.isBlank()) throw new IllegalArgumentException("userAgent is required");
        positive(connectTimeout, "connectTimeout");
        positive(readTimeout, "readTimeout");
        nonNegative(startupDelay, "startupDelay");
        nonNegative(startupFreshness, "startupFreshness");
        positive(fixedDelay, "fixedDelay");
        nonNegative(interRequestDelay, "interRequestDelay");
        positive(identityDirectoryCacheTtl, "identityDirectoryCacheTtl");
        if (maximumIndexBytes <= 0 || maximumInformationTableBytes <= 0) {
            throw new IllegalArgumentException("13F byte limits must be positive");
        }
        if (filingLimit < 2 || filingLimit > 8) throw new IllegalArgumentException("filingLimit must be between 2 and 8");
        managers = List.copyOf(managers == null ? List.of() : managers);
        if (managers.isEmpty()) throw new IllegalArgumentException("at least one 13F manager is required");
    }

    public record Manager(String id, String name, String cik) {
        public Manager {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("manager id and name are required");
            }
            var digits = cik == null ? "" : cik.replaceAll("\\D+", "");
            if (digits.isEmpty() || digits.length() > 10) throw new IllegalArgumentException("invalid manager CIK");
            cik = "0".repeat(10 - digits.length()) + digits;
        }
    }

    private static void https(URI value, String field) {
        if (value == null || !value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URI");
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
