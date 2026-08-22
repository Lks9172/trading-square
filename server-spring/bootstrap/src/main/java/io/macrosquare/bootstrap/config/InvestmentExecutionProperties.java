package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "macrosquare.investment-execution")
public record InvestmentExecutionProperties(
        Path dataDirectory,
        Path legacyDataDirectory,
        boolean importLegacyOnFirstRead
) {
    public InvestmentExecutionProperties {
        requireAbsolute(dataDirectory, "dataDirectory");
        requireAbsolute(legacyDataDirectory, "legacyDataDirectory");
        if (dataDirectory.normalize().equals(legacyDataDirectory.normalize())) {
            throw new IllegalArgumentException("Spring and legacy execution data directories must be different");
        }
    }

    private static void requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
    }
}
