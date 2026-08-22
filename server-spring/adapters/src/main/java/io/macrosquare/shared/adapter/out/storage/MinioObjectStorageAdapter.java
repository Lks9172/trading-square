package io.macrosquare.shared.adapter.out.storage;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded MinIO implementation; PostgreSQL stores metadata, never object bodies. */
public final class MinioObjectStorageAdapter implements ObjectStorage {

    private final MinioClient client;
    private final ObjectArtifactCatalog catalog;
    private final String bucket;
    private final long maximumObjectBytes;
    private final Clock clock;

    public MinioObjectStorageAdapter(
            MinioClient client,
            ObjectArtifactCatalog catalog,
            String bucket,
            long maximumObjectBytes,
            Clock clock
    ) {
        this.client = Objects.requireNonNull(client);
        this.catalog = Objects.requireNonNull(catalog);
        this.bucket = requireBucket(bucket);
        if (maximumObjectBytes <= 0) throw new IllegalArgumentException("maximumObjectBytes must be positive");
        this.maximumObjectBytes = maximumObjectBytes;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<StoredObject> find(String objectKey, long maximumBytes) {
        var key = requireKey(objectKey);
        var bound = Math.min(positive(maximumBytes), maximumObjectBytes);
        try {
            var active = managedKey(key) ? catalog.findActive(bucket, key) : Optional.<ObjectArtifactCatalog.ActiveArtifact>empty();
            // Mutable runtime keys become visible only after the PostgreSQL pointer
            // transaction commits. Seed/source objects are immutable imports and can
            // be read directly without relational metadata.
            if (managedKey(key) && active.isEmpty()) return Optional.empty();
            if (active.isPresent() && active.get().sizeBytes() > bound) {
                throw new ObjectStorageException("Object exceeds its configured read bound: " + key);
            }
            var statRequest = StatObjectArgs.builder().bucket(bucket).object(key);
            active.map(ObjectArtifactCatalog.ActiveArtifact::versionId)
                    .filter(version -> !version.isBlank())
                    .ifPresent(statRequest::versionId);
            var restoredVersionFallback = false;
            io.minio.StatObjectResponse stat;
            try {
                stat = client.statObject(statRequest.build());
            } catch (io.minio.errors.ErrorResponseException error) {
                // S3 version IDs are store-local and can change after a verified
                // backup restore. The stable logical key remains recoverable only
                // when its body still matches the relational SHA-256 pointer.
                if (active.isPresent() && !active.get().versionId().isBlank()
                        && "NoSuchVersion".equals(error.errorResponse().code())) {
                    stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
                    restoredVersionFallback = true;
                } else {
                    throw error;
                }
            }
            if (stat.size() < 0 || stat.size() > bound) {
                throw new ObjectStorageException("Object exceeds its configured read bound: " + key);
            }
            var etagDrift = active.isPresent()
                    && !restoredVersionFallback
                    && !active.get().etag().isBlank()
                    && stat.etag() != null
                    && !active.get().etag().equals(stat.etag());
            if (active.isPresent()) {
                verifySize(key, stat.size(), active.get());
            }
            byte[] content;
            var get = GetObjectArgs.builder().bucket(bucket).object(key);
            // Read the exact version observed by statObject. Without this,
            // a concurrent projection refresh could replace the key between
            // stat and get and produce a torn metadata/body pair.
            if (stat.versionId() != null && !stat.versionId().isBlank()) {
                get.versionId(stat.versionId());
            }
            try (var input = client.getObject(get.build())) {
                content = input.readNBytes(Math.toIntExact(stat.size() + 1));
            }
            if (content.length != stat.size() || content.length > bound) {
                throw new ObjectStorageException("Object size changed during bounded read: " + key);
            }
            if (active.isPresent() && !sha256(content).equals(active.get().checksumSha256())) {
                throw new ObjectStorageException("Object checksum does not match its active pointer: " + key);
            }
            // ETags are storage-local validators and can be rewritten by a
            // verified restore or S3 implementation change. SHA-256 remains
            // the integrity authority. Once the exact body matches, repair
            // the relational pointer instead of forcing a network refetch.
            if ((restoredVersionFallback || etagDrift) && active.isPresent()) {
                catalog.recordAndActivate(new ObjectArtifactCatalog.Artifact(
                        bucket,
                        key,
                        stat.versionId(),
                        stat.etag(),
                        active.get().checksumSha256(),
                        stat.contentType(),
                        stat.size(),
                        Map.of("recovered", restoredVersionFallback ? "version-id-remap" : "etag-remap"),
                        clock.instant()
                ));
            }
            return Optional.of(new StoredObject(
                    key,
                    content,
                    stat.contentType(),
                    stat.etag(),
                    stat.versionId(),
                    stat.lastModified() == null ? clock.instant() : stat.lastModified().toInstant()
            ));
        } catch (io.minio.errors.ErrorResponseException error) {
            if ("NoSuchKey".equals(error.errorResponse().code())
                    || "NoSuchObject".equals(error.errorResponse().code())) return Optional.empty();
            throw new ObjectStorageException("Unable to read object " + key, error);
        } catch (ObjectStorageException error) {
            throw error;
        } catch (Exception error) {
            throw new ObjectStorageException("Unable to read object " + key, error);
        }
    }

    @Override
    public StoredObject put(String objectKey, byte[] content, String contentType, Map<String, String> metadata) {
        var key = requireKey(objectKey);
        Objects.requireNonNull(content, "content");
        if (content.length == 0 || content.length > maximumObjectBytes) {
            throw new IllegalArgumentException("object content exceeds its configured bound");
        }
        var normalizedType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        var safeMetadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        try {
            var response = client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .contentType(normalizedType)
                    .userMetadata(safeMetadata)
                    .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                    .build());
            var now = clock.instant();
            var checksum = sha256(content);
            catalog.recordAndActivate(new ObjectArtifactCatalog.Artifact(
                    bucket,
                    key,
                    response.versionId(),
                    response.etag(),
                    checksum,
                    normalizedType,
                    content.length,
                    safeMetadata,
                    now
            ));
            return new StoredObject(key, content, normalizedType, response.etag(), response.versionId(), now);
        } catch (ObjectStorageException error) {
            throw error;
        } catch (Exception error) {
            throw new ObjectStorageException("Unable to write object " + key, error);
        }
    }

    @Override
    public java.util.List<String> list(String prefix, int limit) {
        var safePrefix = requirePrefix(prefix);
        if (limit <= 0 || limit > 10_000) throw new IllegalArgumentException("object list limit is invalid");
        try {
            var result = new ArrayList<String>();
            for (var item : client.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(safePrefix).recursive(true).build())) {
                result.add(item.get().objectName());
                if (result.size() >= limit) break;
            }
            return List.copyOf(result);
        } catch (Exception error) {
            throw new ObjectStorageException("Unable to list objects", error);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static boolean managedKey(String key) {
        return key.startsWith("projections/") || key.startsWith("sec-filings/")
                || key.startsWith("source-documents/");
    }

    private static void verifySize(
            String key,
            long actualSize,
            ObjectArtifactCatalog.ActiveArtifact expected
    ) {
        if (actualSize != expected.sizeBytes()) {
            throw new ObjectStorageException("Object size does not match its active pointer: " + key);
        }
    }

    private static String requireBucket(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("invalid object-storage bucket");
        }
        return value;
    }

    private static String requireKey(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.startsWith("/")
                || value.contains("..") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid object key");
        }
        return value;
    }

    private static String requirePrefix(String value) {
        if (value == null || value.length() > 900 || value.startsWith("/") || value.contains("..")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid object prefix");
        }
        return value;
    }

    private static long positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
        return value;
    }
}
