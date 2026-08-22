package io.macrosquare.shared.adapter.out.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists object metadata and the active relational pointer for mutable logical keys.
 *
 * <p>The object body is written first. Only a successful catalog transaction makes that
 * exact MinIO version visible to readers. A database failure can therefore leave a harmless
 * unreferenced object version, but can never publish a partial projection.</p>
 */
public interface ObjectArtifactCatalog {

    void recordAndActivate(Artifact artifact);

    Optional<ActiveArtifact> findActive(String bucket, String objectKey);

    record Artifact(
            String bucket,
            String objectKey,
            String versionId,
            String etag,
            String checksumSha256,
            String contentType,
            long sizeBytes,
            Map<String, String> metadata,
            Instant createdAt
    ) {
        public Artifact {
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
            versionId = versionId == null ? "" : versionId;
            bucket = Objects.requireNonNull(bucket, "bucket");
            objectKey = Objects.requireNonNull(objectKey, "objectKey");
            etag = etag == null ? "" : etag;
            if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("artifact checksum must be lowercase SHA-256");
            }
            contentType = contentType == null || contentType.isBlank()
                    ? "application/octet-stream" : contentType;
            if (sizeBytes < 0) throw new IllegalArgumentException("artifact size must not be negative");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    record ActiveArtifact(
            String versionId,
            String etag,
            String checksumSha256,
            long sizeBytes,
            String contentType,
            Instant activatedAt
    ) {
        public ActiveArtifact {
            versionId = versionId == null ? "" : versionId;
            etag = etag == null ? "" : etag;
            checksumSha256 = checksumSha256 == null ? "" : checksumSha256;
            if (!checksumSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("active artifact checksum must be lowercase SHA-256");
            }
            contentType = contentType == null ? "application/octet-stream" : contentType;
            if (sizeBytes < 0) throw new IllegalArgumentException("active artifact size must not be negative");
            activatedAt = Objects.requireNonNull(activatedAt, "activatedAt");
        }
    }
}
