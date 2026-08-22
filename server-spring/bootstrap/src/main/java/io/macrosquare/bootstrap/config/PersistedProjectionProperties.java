package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "macrosquare.persisted-projections")
public record PersistedProjectionProperties(
        ReadMode researchCatalogReadMode,
        ReadMode cryptoReadMode,
        Path directory,
        long maximumFileBytes,
        int maximumCachedFiles
) {
    public PersistedProjectionProperties {
        if (researchCatalogReadMode == null) throw new IllegalArgumentException("researchCatalogReadMode is required");
        if (cryptoReadMode == null) throw new IllegalArgumentException("cryptoReadMode is required");
        if (directory == null || !directory.isAbsolute()) {
            throw new IllegalArgumentException("persisted projection directory must be absolute");
        }
        directory = directory.normalize();
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        if (maximumCachedFiles <= 0) throw new IllegalArgumentException("maximumCachedFiles must be positive");
    }

    public enum ReadMode {
        FILE_PREFERRED,
        FILE_ONLY
    }
}
