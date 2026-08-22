package io.macrosquare.shared.adapter.out.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresTemporalTest {

    @Test
    void convertsInstantToAnExplicitUtcJdbcTypeWithoutChangingTheMoment() {
        var instant = Instant.parse("2026-07-21T02:02:42.123456Z");

        var timestamp = PostgresTemporal.timestamp(instant);

        assertThat(timestamp.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(timestamp.toInstant()).isEqualTo(instant);
    }
}
