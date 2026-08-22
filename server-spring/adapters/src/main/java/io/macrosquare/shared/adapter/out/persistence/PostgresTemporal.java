package io.macrosquare.shared.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/** Explicit PostgreSQL {@code timestamptz} boundary conversion for JDBC parameters. */
public final class PostgresTemporal {

    private PostgresTemporal() {
    }

    public static OffsetDateTime timestamp(Instant instant) {
        return Objects.requireNonNull(instant, "instant").atOffset(ZoneOffset.UTC);
    }
}
