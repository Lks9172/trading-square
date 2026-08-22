package io.macrosquare.shared.adapter.out.storage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter-internal abstraction over an S3-compatible object store.
 *
 * <p>This type deliberately lives in the outer adapter layer: neither the domain nor the
 * application layer needs to know about buckets, ETags, object versions, or byte streams.</p>
 */
public interface ObjectStorage {

    Optional<StoredObject> find(String objectKey, long maximumBytes);

    StoredObject put(
            String objectKey,
            byte[] content,
            String contentType,
            Map<String, String> metadata
    );

    List<String> list(String prefix, int limit);

    record StoredObject(
            String objectKey,
            byte[] content,
            String contentType,
            String etag,
            String versionId,
            Instant lastModified
    ) {
        public StoredObject {
            content = content.clone();
            contentType = contentType == null ? "application/octet-stream" : contentType;
            etag = etag == null ? "" : etag;
            versionId = versionId == null ? "" : versionId;
            lastModified = lastModified == null ? Instant.EPOCH : lastModified;
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
