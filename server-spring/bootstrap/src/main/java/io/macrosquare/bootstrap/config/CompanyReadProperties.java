package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.company-read")
public record CompanyReadProperties(
        ReadMode readMode,
        Path sourceCacheDirectory,
        long maximumFileBytes,
        int maximumCachedFiles,
        Duration cacheTtl
) {
    public CompanyReadProperties {
        if (readMode == null) throw new IllegalArgumentException("company readMode is required");
        if (sourceCacheDirectory == null || !sourceCacheDirectory.isAbsolute()) {
            throw new IllegalArgumentException("company sourceCacheDirectory must be absolute");
        }
        sourceCacheDirectory = sourceCacheDirectory.normalize();
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("company maximumFileBytes must be positive");
        if (maximumCachedFiles <= 0) throw new IllegalArgumentException("company maximumCachedFiles must be positive");
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("company cacheTtl must be positive");
        }
    }

    public enum ReadMode {
        FILE_PREFERRED,
        FILE_ONLY
    }
}
