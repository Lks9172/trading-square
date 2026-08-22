package io.macrosquare.bootstrap.health;

import io.macrosquare.bootstrap.config.ObjectStorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/** Readiness check for the internal MinIO API and configured versioned bucket. */
public final class ObjectStorageHealthIndicator implements HealthIndicator {

    private final MinioClient client;
    private final String bucket;

    public ObjectStorageHealthIndicator(MinioClient client, ObjectStorageProperties properties) {
        this.client = Objects.requireNonNull(client);
        this.bucket = Objects.requireNonNull(properties).bucket();
    }

    @Override
    public Health health() {
        try {
            var exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            return exists
                    ? Health.up().withDetail("bucket", bucket).build()
                    : Health.down().withDetail("reason", "configured bucket is missing").build();
        } catch (Exception error) {
            return Health.down(error).withDetail("bucket", bucket).build();
        }
    }
}
