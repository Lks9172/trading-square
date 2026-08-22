package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "macrosquare.investment-execution")
public record InvestmentExecutionProperties(
        Path dataDirectory
) {
    public InvestmentExecutionProperties {
        requireAbsolute(dataDirectory, "dataDirectory");
    }

    private static void requireAbsolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
    }
}
