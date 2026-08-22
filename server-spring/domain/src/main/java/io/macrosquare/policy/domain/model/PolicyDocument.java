package io.macrosquare.policy.domain.model;

import java.time.Instant;
import java.util.Objects;

public record PolicyDocument(
        String id,
        String source,
        String title,
        PolicyDocumentType type,
        Instant publishedAt,
        String url,
        String text
) {
    public PolicyDocument {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("document id is required");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url is required");
        text = text == null ? "" : text.trim();
    }
}
