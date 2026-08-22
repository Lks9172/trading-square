package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "macrosquare.persistence")
public record PersistenceProperties(
        Mode mode,
        boolean legacyImportEnabled,
        int exclusiveTaskMaxConcurrency
) {

    public PersistenceProperties {
        if (mode == null) throw new IllegalArgumentException("persistence mode is required");
        if (exclusiveTaskMaxConcurrency < 1 || exclusiveTaskMaxConcurrency > 32) {
            throw new IllegalArgumentException("exclusiveTaskMaxConcurrency must be between 1 and 32");
        }
    }

    public enum Mode {
        LEGACY_FILE,
        POSTGRES_MINIO
    }
}
