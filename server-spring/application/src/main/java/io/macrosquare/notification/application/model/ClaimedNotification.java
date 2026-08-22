package io.macrosquare.notification.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Outbox item leased to exactly one dispatcher for a bounded interval. */
public record ClaimedNotification(
        UUID id,
        String idempotencyKey,
        String operation,
        String text,
        int attempts,
        String leaseOwner,
        Instant leasedUntil
) {
    public ClaimedNotification {
        Objects.requireNonNull(id, "id");
        idempotencyKey = require(idempotencyKey, "idempotencyKey");
        operation = require(operation, "operation");
        text = require(text, "text");
        leaseOwner = require(leaseOwner, "leaseOwner");
        Objects.requireNonNull(leasedUntil, "leasedUntil");
        if (attempts < 1) throw new IllegalArgumentException("attempts must be positive");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
