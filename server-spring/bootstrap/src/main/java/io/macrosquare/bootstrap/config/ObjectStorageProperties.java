package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.object-storage")
public record ObjectStorageProperties(
        URI endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        long maximumObjectBytes,
        long maximumProjectionBytes,
        int maximumCachedDocuments,
        Duration projectionCacheTtl
) {
    public ObjectStorageProperties {
        if (endpoint == null || endpoint.getScheme() == null
                || !(endpoint.getScheme().equals("http") || endpoint.getScheme().equals("https"))) {
            throw new IllegalArgumentException("object-storage endpoint must be HTTP(S)");
        }
        if (accessKey == null || accessKey.isBlank()) throw new IllegalArgumentException("object-storage accessKey is required");
        if (secretKey == null || secretKey.length() < 8) throw new IllegalArgumentException("object-storage secretKey is invalid");
        if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("object-storage bucket is required");
        if (maximumObjectBytes <= 0 || maximumProjectionBytes <= 0
                || maximumProjectionBytes > maximumObjectBytes) {
            throw new IllegalArgumentException("object-storage byte limits are invalid");
        }
        if (maximumCachedDocuments <= 0) {
            throw new IllegalArgumentException("object-storage cache bound must be positive");
        }
        if (projectionCacheTtl == null || projectionCacheTtl.isNegative() || projectionCacheTtl.isZero()
                || projectionCacheTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("object-storage projection cache TTL must be at most one day");
        }
    }
}
