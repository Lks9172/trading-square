package io.macrosquare.notification.application.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Durable, transport-neutral notification command stored before delivery. */
public record OutboundNotification(
        UUID id,
        String idempotencyKey,
        String operation,
        String text,
        Instant createdAt
) {
    private static final Pattern OPERATION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[0-9a-f]{64}");

    public OutboundNotification {
        Objects.requireNonNull(id, "id");
        idempotencyKey = require(idempotencyKey, "idempotencyKey");
        operation = require(operation, "operation");
        text = require(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("idempotencyKey must be a SHA-256 hex value");
        }
        if (!OPERATION.matcher(operation).matches()) {
            throw new IllegalArgumentException("invalid notification operation");
        }
        if (text.length() > 64_000) throw new IllegalArgumentException("notification text is too long");
    }

    public static OutboundNotification create(
            String operation,
            String deduplicationMaterial,
            String text,
            Instant createdAt
    ) {
        var normalizedOperation = require(operation, "operation");
        var material = require(deduplicationMaterial, "deduplicationMaterial");
        var key = sha256(normalizedOperation + '\n' + material);
        return new OutboundNotification(UUID.randomUUID(), key, normalizedOperation, text, createdAt);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
