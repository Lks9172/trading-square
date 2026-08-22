package io.macrosquare.shared.adapter.out.storage;

import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL catalog for immutable MinIO object versions. */
public final class JdbcObjectArtifactCatalog implements ObjectArtifactCatalog {

    private static final String UPSERT = """
            insert into storage.object_artifact (
                id, bucket, object_key, version_id, etag, checksum_sha256,
                content_type, size_bytes, metadata, created_at
            ) values (
                :id, :bucket, :objectKey, :versionId, :etag, :checksum,
                :contentType, :sizeBytes, cast(:metadata as jsonb), :createdAt
            )
            on conflict (bucket, object_key, version_id) do update set
                etag = excluded.etag,
                checksum_sha256 = excluded.checksum_sha256,
                content_type = excluded.content_type,
                size_bytes = excluded.size_bytes,
                metadata = excluded.metadata
            """;

    private static final String ACTIVATE = """
            insert into storage.object_pointer (
                bucket, object_key, artifact_id, generation, activated_at
            ) values (
                :bucket, :objectKey, :id, 1, :createdAt
            )
            on conflict (bucket, object_key) do update set
                artifact_id = excluded.artifact_id,
                generation = storage.object_pointer.generation + 1,
                activated_at = excluded.activated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionOperations transactions;

    public JdbcObjectArtifactCatalog(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public void recordAndActivate(Artifact artifact) {
        Objects.requireNonNull(artifact);
        try {
            var id = artifactId(artifact);
            var parameters = new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("bucket", artifact.bucket())
                    .addValue("objectKey", artifact.objectKey())
                    .addValue("versionId", artifact.versionId())
                    .addValue("etag", artifact.etag())
                    .addValue("checksum", artifact.checksumSha256())
                    .addValue("contentType", artifact.contentType())
                    .addValue("sizeBytes", artifact.sizeBytes())
                    .addValue("metadata", objectMapper.writeValueAsString(artifact.metadata()))
                    .addValue("createdAt", PostgresTemporal.timestamp(artifact.createdAt()));
            transactions.executeWithoutResult(ignored -> {
                jdbc.update(UPSERT, parameters);
                jdbc.update(ACTIVATE, parameters);
            });
        } catch (Exception error) {
            throw new ObjectStorageException("Unable to activate object metadata", error);
        }
    }

    @Override
    public Optional<ActiveArtifact> findActive(String bucket, String objectKey) {
        try {
            return jdbc.query("""
                    select a.version_id, a.etag, a.checksum_sha256, a.size_bytes,
                           a.content_type, p.activated_at
                    from storage.object_pointer p
                    join storage.object_artifact a on a.id = p.artifact_id
                    where p.bucket = :bucket and p.object_key = :objectKey
                    """, new MapSqlParameterSource()
                    .addValue("bucket", bucket)
                    .addValue("objectKey", objectKey), (row, ignored) -> new ActiveArtifact(
                    row.getString("version_id"),
                    row.getString("etag"),
                    row.getString("checksum_sha256"),
                    row.getLong("size_bytes"),
                    row.getString("content_type"),
                    row.getObject("activated_at", java.time.OffsetDateTime.class).toInstant()
            )).stream().findFirst();
        } catch (RuntimeException error) {
            throw new ObjectStorageException("Unable to resolve active object metadata", error);
        }
    }

    private static UUID artifactId(Artifact artifact) {
        var identity = artifact.bucket() + '\n' + artifact.objectKey() + '\n' + artifact.versionId();
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
