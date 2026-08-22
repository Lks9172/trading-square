package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.market-data")
public record MarketDataProperties(
        ReadMode readMode,
        Path snapshotFile,
        Path seedSnapshotFile,
        Path historyDirectory,
        long maximumSnapshotBytes,
        long maximumHistoryFileBytes,
        int maximumHistoryFiles,
        Duration cacheTtl
) {
    public MarketDataProperties {
        if (readMode == null) throw new IllegalArgumentException("market data readMode is required");
        snapshotFile = absolute(snapshotFile, "snapshotFile");
        seedSnapshotFile = absolute(seedSnapshotFile, "seedSnapshotFile");
        historyDirectory = absolute(historyDirectory, "historyDirectory");
        if (maximumSnapshotBytes <= 0) throw new IllegalArgumentException("maximumSnapshotBytes must be positive");
        if (maximumHistoryFileBytes <= 0) throw new IllegalArgumentException("maximumHistoryFileBytes must be positive");
        if (maximumHistoryFiles <= 0) throw new IllegalArgumentException("maximumHistoryFiles must be positive");
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
    }

    private static Path absolute(Path value, String field) {
        if (value == null || !value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value.normalize();
    }

    public enum ReadMode {
        SPRING_NATIVE,
        FILE_PREFERRED,
        FILE_ONLY
    }
}
